package com.angatusistemas.whatsapp.bridge;

import br.com.angatusistemas.lib.console.Console;
import com.angatusistemas.whatsapp.DisconnectCause;
import com.angatusistemas.whatsapp.exception.BridgeErrorCode;
import com.angatusistemas.whatsapp.exception.ConnectionException;
import com.angatusistemas.whatsapp.internal.Threads;
import com.angatusistemas.whatsapp.options.BridgeOptions;
import com.google.gson.JsonObject;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The one bridge process of this JVM and its WebSocket, shared by every client: N numbers cost one
 * Node.js runtime instead of N. It starts with the first client, restarts on its own with backoff
 * when the process dies or the link drops (telling each client to re-create its account on the new
 * process), routes every event to the client it belongs to, and shuts down with the last client or
 * the JVM.
 *
 * @author Angatu Sistemas
 */
public final class BridgeHost {

    private static final BridgeHost SHARED = new BridgeHost();
    private static final Duration PORT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration SHUTDOWN_REQUEST_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration STOP_GRACE = Duration.ofSeconds(5);
    private static final long MAX_RESTART_DELAY_MS = 30_000;
    private static final long COOLDOWN_DELAY_MS = 60_000;
    private static final int FAILURES_BEFORE_COOLDOWN = 10;
    private static final Duration CRASH_GRACE = Duration.ofSeconds(1);

    private final Map<String, Attachment> attachments = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private volatile BridgeProcess process;
    private volatile BridgeConnection connection;
    private BridgeOptions options;
    private CompletableFuture<Void> starting;
    private ScheduledFuture<?> restart;
    private int failures;
    private boolean hookRegistered;
    private volatile boolean stopping;

    private BridgeHost() {
    }

    public static BridgeHost shared() {
        return SHARED;
    }

    public void attach(String instanceId, Attachment attachment, BridgeOptions requested) {
        lock.lock();
        try {
            if (options == null || (process == null && attachments.isEmpty())) {
                options = requested;
            } else if (!options.equals(requested)) {
                Console.warn("[whatsapp] bridge already configured by another client; options of '%s' ignored", instanceId);
            }
            attachments.put(instanceId, attachment);
            stopping = false;
        } finally {
            lock.unlock();
        }
    }

    public void detach(String instanceId) {
        attachments.remove(instanceId);
        if (attachments.isEmpty()) {
            shutdown();
        }
    }

    public boolean isOpen() {
        BridgeConnection current = connection;
        return current != null && current.isOpen();
    }

    public long pid() {
        BridgeProcess current = process;
        return current == null ? -1 : current.pid();
    }

    public CompletableFuture<JsonObject> request(String action, String instanceId, JsonObject payload, Duration timeout) {
        BridgeConnection current = connection;
        if (current == null || !current.isOpen()) {
            return CompletableFuture.failedFuture(new ConnectionException(instanceId, BridgeErrorCode.NOT_CONNECTED,
                    "the bridge is not running; call connect() first"));
        }
        return current.request(action, instanceId, payload, timeout);
    }

    public CompletableFuture<Void> ensureRunning() {
        lock.lock();
        try {
            if (isOpen()) {
                return CompletableFuture.completedFuture(null);
            }
            if (starting == null) {
                CompletableFuture<Void> attempt = CompletableFuture.runAsync(() -> start(false), Threads.VIRTUAL);
                starting = attempt;
                attempt.whenComplete((ignored, error) -> {
                    lock.lock();
                    try {
                        starting = null;
                    } finally {
                        lock.unlock();
                    }
                });
            }
            return starting;
        } finally {
            lock.unlock();
        }
    }

    private void start(boolean notifyClients) {
        registerShutdownHook();
        BridgeProcess current = process;
        if (current == null || !current.isAlive()) {
            current = BridgeProcess.start(options);
            process = current;
        }
        try {
            int port = current.port().get(PORT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            String host = options.host() == null || options.host().equals("0.0.0.0") ? "127.0.0.1" : options.host();
            BridgeConnection link = new BridgeConnection(URI.create("ws://" + host + ":" + port), current.token(), this::route);
            link.open(HANDSHAKE_TIMEOUT).get();
            connection = link;
            failures = 0;
            link.closed().thenAccept(detail -> onClosed(link, detail));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConnectionException(null, BridgeErrorCode.CONNECTION_FAILED, "interrupted while starting the bridge", e);
        } catch (ExecutionException | java.util.concurrent.TimeoutException e) {
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            throw new ConnectionException(null, BridgeErrorCode.CONNECTION_FAILED, "could not reach the bridge: " + cause.getMessage(), cause);
        }
        if (notifyClients) {
            attachments.values().forEach(attachment -> Threads.VIRTUAL.execute(attachment::onBridgeReady));
        }
    }

    private void route(Frame.Event event) {
        Attachment attachment = event.instanceId() == null ? null : attachments.get(event.instanceId());
        if (attachment != null) {
            attachment.onBridgeEvent(event.event(), event.data());
        }
    }

    private void onClosed(BridgeConnection link, String detail) {
        if (link != connection || stopping) {
            return;
        }
        connection = null;
        Threads.VIRTUAL.execute(() -> classifyAndRecover(detail));
    }

    // The socket usually closes before the OS reports the process dead: give the exit a moment to show.
    private void classifyAndRecover(String detail) {
        BridgeProcess current = process;
        boolean crashed = current == null || current.exitsWithin(CRASH_GRACE);
        DisconnectCause cause = crashed ? DisconnectCause.BRIDGE_CRASH : DisconnectCause.CONNECTION_LOST;
        Console.warn("[whatsapp] bridge link lost (%s, %s) - reconnecting", cause, detail);
        attachments.values().forEach(attachment -> Threads.VIRTUAL.execute(() -> attachment.onBridgeLost(cause, detail)));
        scheduleRestart();
    }

    private void scheduleRestart() {
        lock.lock();
        try {
            if (stopping || attachments.isEmpty() || (restart != null && !restart.isDone())) {
                return;
            }
            long delay = failures >= FAILURES_BEFORE_COOLDOWN
                    ? COOLDOWN_DELAY_MS
                    : Math.min(MAX_RESTART_DELAY_MS, 1_000L << Math.min(failures, 5));
            delay += ThreadLocalRandom.current().nextLong(500);
            restart = Threads.TIMER.schedule(() -> Threads.VIRTUAL.execute(this::restart), delay, TimeUnit.MILLISECONDS);
        } finally {
            lock.unlock();
        }
    }

    private void restart() {
        if (stopping || attachments.isEmpty() || isOpen()) {
            return;
        }
        try {
            lock.lock();
            try {
                start(true);
            } finally {
                lock.unlock();
            }
            Console.info("[whatsapp] bridge link restored");
        } catch (RuntimeException e) {
            failures++;
            Console.warn("[whatsapp] bridge restart attempt %s failed: %s", failures, e.getMessage());
            scheduleRestart();
        }
    }

    private void shutdown() {
        lock.lock();
        try {
            if (!attachments.isEmpty()) {
                return;
            }
            stopping = true;
            if (restart != null) {
                restart.cancel(false);
            }
            BridgeConnection link = connection;
            connection = null;
            if (link != null && link.isOpen()) {
                try {
                    link.request("shutdown", null, new JsonObject(), SHUTDOWN_REQUEST_TIMEOUT)
                            .get(SHUTDOWN_REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException | java.util.concurrent.TimeoutException e) {
                    // the process is stopped below either way
                }
                link.close();
            }
            BridgeProcess current = process;
            process = null;
            if (current != null) {
                current.stop(STOP_GRACE);
            }
        } finally {
            lock.unlock();
        }
    }

    private void registerShutdownHook() {
        if (hookRegistered) {
            return;
        }
        hookRegistered = true;
        // A bridge left behind by a JVM that exited keeps the WhatsApp socket open and fights the next start.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            attachments.clear();
            shutdown();
        }, "angatu-whatsapp-shutdown"));
    }

    public interface Attachment {

        void onBridgeEvent(String event, JsonObject data);

        void onBridgeReady();

        void onBridgeLost(DisconnectCause cause, String detail);
    }
}
