package com.angatusistemas.whatsapp.bridge;

import br.com.angatusistemas.lib.console.Console;
import com.angatusistemas.whatsapp.exception.BridgeErrorCode;
import com.angatusistemas.whatsapp.exception.BridgeException;
import com.angatusistemas.whatsapp.exception.BridgeExceptionTranslator;
import com.angatusistemas.whatsapp.exception.ConnectionException;
import com.angatusistemas.whatsapp.exception.TimeoutException;
import com.angatusistemas.whatsapp.internal.Threads;
import com.google.gson.JsonObject;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

/**
 * One authenticated WebSocket to the local bridge: correlates requests with responses, keeps the
 * link alive with heartbeats on the shared timer, and passes events on. Responses are completed
 * without running caller code on the socket thread; every pending request fails the moment the
 * link closes, so nothing waits forever.
 *
 * @author Angatu Sistemas
 */
public final class BridgeConnection {

    private static final long HEARTBEAT_INTERVAL_MS = 15_000;
    private static final int CLOSE_HEARTBEAT_TIMEOUT = 4900;

    private final String token;
    private final Consumer<Frame.Event> events;
    private final WebSocketClient client;
    private final Map<String, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final CompletableFuture<Frame.HandshakeAck> handshake = new CompletableFuture<>();
    private final CompletableFuture<String> closed = new CompletableFuture<>();
    private final AtomicLong ids = new AtomicLong();
    private final AtomicBoolean awaitingAck = new AtomicBoolean();
    private volatile boolean open;
    private volatile ScheduledFuture<?> heartbeat;

    public BridgeConnection(URI uri, String token, Consumer<Frame.Event> events) {
        this.token = token;
        this.events = events;
        this.client = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake serverHandshake) {
                send(Frame.handshake(nextId(), BridgeConnection.this.token));
            }

            @Override
            public void onMessage(String text) {
                route(Frame.decode(text));
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                closedWith("code=" + code + " reason=" + reason + " remote=" + remote);
            }

            @Override
            public void onError(Exception e) {
                // onClose always follows and does the cleanup.
                Console.debug("[whatsapp] bridge link error: %s", e.getMessage());
            }
        };
        // Heartbeats below are the liveness check the bridge also tracks; the library's own ping would only disagree.
        this.client.setConnectionLostTimeout(0);
        this.client.setTcpNoDelay(true);
    }

    public CompletableFuture<Frame.HandshakeAck> open(Duration timeout) {
        client.connect();
        return handshake.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS).whenComplete((ack, error) -> {
            if (error != null) {
                close();
            }
        });
    }

    public CompletableFuture<JsonObject> request(String action, String instanceId, JsonObject payload, Duration timeout) {
        if (!open) {
            return CompletableFuture.failedFuture(new ConnectionException(instanceId, BridgeErrorCode.NOT_CONNECTED,
                    "bridge link is not open; '" + action + "' not sent"));
        }
        String id = nextId();
        CompletableFuture<JsonObject> response = new CompletableFuture<>();
        pending.put(id, response);
        try {
            client.send(Frame.request(id, action, instanceId, payload));
        } catch (RuntimeException e) {
            pending.remove(id);
            return CompletableFuture.failedFuture(new ConnectionException(instanceId, BridgeErrorCode.NOT_CONNECTED,
                    "failed to send '" + action + "' to the bridge", e));
        }
        ScheduledFuture<?> expiry = Threads.TIMER.schedule(() -> {
            CompletableFuture<JsonObject> expired = pending.remove(id);
            if (expired != null) {
                failAsync(expired, new TimeoutException(instanceId, BridgeErrorCode.TIMEOUT,
                        "'" + action + "' got no answer within " + timeout.toSeconds() + " s"));
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        return response.whenComplete((data, error) -> expiry.cancel(false));
    }

    public boolean isOpen() {
        return open;
    }

    public CompletableFuture<String> closed() {
        return closed;
    }

    public void close() {
        open = false;
        client.close();
    }

    private void route(Frame frame) {
        switch (frame) {
            case Frame.HandshakeAck ack -> acknowledge(ack);
            case Frame.HeartbeatAck ignored -> awaitingAck.set(false);
            case Frame.Response response -> answer(response);
            case Frame.Event event -> events.accept(event);
            case Frame.ProtocolError error -> protocolError(error);
            case Frame.Unknown unknown -> Console.debug("[whatsapp] ignoring bridge frame: %s", unknown.reason());
        }
    }

    private void acknowledge(Frame.HandshakeAck ack) {
        if (!Frame.isCompatible(ack.protocolVersion())) {
            handshake.completeExceptionally(new BridgeException(null, BridgeErrorCode.PROTOCOL_VERSION_MISMATCH,
                    "bridge protocol " + ack.protocolVersion() + " is incompatible with SDK protocol " + Frame.PROTOCOL_VERSION));
            close();
            return;
        }
        open = true;
        heartbeat = Threads.TIMER.scheduleWithFixedDelay(this::beat, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
        handshake.complete(ack);
    }

    private void answer(Frame.Response response) {
        CompletableFuture<JsonObject> future = pending.remove(response.id());
        if (future == null) {
            return;
        }
        if (response.success()) {
            Threads.VIRTUAL.execute(() -> future.complete(response.data()));
        } else {
            failAsync(future, BridgeExceptionTranslator.translate(null,
                    response.errorCode() == null ? BridgeErrorCode.INTERNAL_ERROR : response.errorCode(), response.errorMessage()));
        }
    }

    private void protocolError(Frame.ProtocolError error) {
        RuntimeException failure = BridgeExceptionTranslator.translate(null, error.code(), error.message());
        if (!handshake.isDone()) {
            handshake.completeExceptionally(failure);
            return;
        }
        CompletableFuture<JsonObject> future = error.id() == null ? null : pending.remove(error.id());
        if (future != null) {
            failAsync(future, failure);
        }
    }

    private void beat() {
        if (!open) {
            return;
        }
        if (!awaitingAck.compareAndSet(false, true)) {
            Console.warn("[whatsapp] bridge did not answer the last heartbeat - closing the link to reconnect");
            open = false;
            client.closeConnection(CLOSE_HEARTBEAT_TIMEOUT, "heartbeat timeout");
            return;
        }
        try {
            client.send(Frame.heartbeat(nextId()));
        } catch (RuntimeException e) {
            Console.debug("[whatsapp] heartbeat not sent: %s", e.getMessage());
        }
    }

    private void closedWith(String detail) {
        open = false;
        ScheduledFuture<?> beat = heartbeat;
        if (beat != null) {
            beat.cancel(false);
        }
        // CONNECTION_FAILED, not NOT_CONNECTED: a request in flight may already have reached WhatsApp.
        ConnectionException failure = new ConnectionException(null, BridgeErrorCode.CONNECTION_FAILED, "bridge link closed (" + detail + ")");
        handshake.completeExceptionally(failure);
        for (String id : pending.keySet()) {
            CompletableFuture<JsonObject> future = pending.remove(id);
            if (future != null) {
                failAsync(future, failure);
            }
        }
        closed.complete(detail);
    }

    private String nextId() {
        return Long.toString(ids.incrementAndGet(), 36);
    }

    private static void failAsync(CompletableFuture<JsonObject> future, Throwable failure) {
        Threads.VIRTUAL.execute(() -> future.completeExceptionally(failure));
    }
}
