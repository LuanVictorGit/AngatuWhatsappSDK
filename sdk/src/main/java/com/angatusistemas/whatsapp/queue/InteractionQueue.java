package com.angatusistemas.whatsapp.queue;

import br.com.angatusistemas.lib.console.Console;
import com.angatusistemas.whatsapp.exception.BridgeErrorCode;
import com.angatusistemas.whatsapp.exception.QueueException;
import com.angatusistemas.whatsapp.exception.WhatsAppException;
import com.angatusistemas.whatsapp.internal.Threads;
import com.angatusistemas.whatsapp.model.PendingInteraction;
import com.angatusistemas.whatsapp.model.Presence;
import com.angatusistemas.whatsapp.options.HumanizationOptions;
import com.angatusistemas.whatsapp.options.PacingOptions;
import com.angatusistemas.whatsapp.options.ReadReceiptMode;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * The interaction queue of one account: every operation that reaches WhatsApp goes through it, one
 * at a time, at least 500 ms apart. Rows live in SQLite (QueuedInteraction), so a long backlog
 * costs disk instead of heap and survives a crash; only the futures of callers still waiting are
 * in memory. One virtual thread works the queue, sleeping between turns at no cost.
 *
 * A send is a small human act: the blue ticks on the chat first when replying, then "typing..."
 * for a time proportional to the text, then the message, then the random gap before the next
 * send. A row picked while the account is offline waits; a refusal from the bridge that happened
 * before anything was sent is retried; an outcome that is unknown (a send whose link dropped) is
 * reported, never repeated, because a duplicate message is worse than a failed one.
 *
 * @author Angatu Sistemas
 */
public final class InteractionQueue implements AutoCloseable {

    private static final long RETRY_DELAY_MS = 5_000;
    private static final int MAX_ATTEMPTS = 5;
    private static final long IDLE_WAIT_MS = 30_000;
    private static final long MIN_WAIT_MS = 50;
    private static final long NOT_READY_WAIT_MS = 1_000;
    private static final int MAX_TRACKED_UNREAD = 10_000;
    private static final long JITTER_MS = 150;
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final String instanceId;
    private final Gateway gateway;
    private final Supplier<PacingOptions> pacing;
    private final Supplier<HumanizationOptions> humanization;
    private final Map<String, CompletableFuture<JsonObject>> waiting = new ConcurrentHashMap<>();
    private final Set<String> unreadChats = ConcurrentHashMap.newKeySet();
    private final RateWindow sends = new RateWindow();
    private final RateWindow reads = new RateWindow();
    private volatile Thread worker;
    private volatile boolean closed;
    private volatile String running;
    private long nextSendAt;
    private long lastCallAt;

    public InteractionQueue(String instanceId, Gateway gateway, Supplier<PacingOptions> pacing, Supplier<HumanizationOptions> humanization) {
        this.instanceId = instanceId;
        this.gateway = gateway;
        this.pacing = pacing;
        this.humanization = humanization;
    }

    public synchronized void start() {
        if (worker == null && !closed) {
            InteractionStore.ensureIndexes();
            worker = Thread.ofVirtual().name("angatu-whatsapp-queue-" + instanceId).start(this::work);
        }
    }

    public CompletableFuture<JsonObject> submit(Interaction interaction) {
        if (closed) {
            return CompletableFuture.failedFuture(new QueueException(instanceId, BridgeErrorCode.QUEUE_CLOSED, "client is closed"));
        }
        InteractionStore.ensureIndexes();
        if (interaction.dedupeKey() != null) {
            for (QueuedInteraction previous : InteractionStore.pendingWithKey(instanceId, interaction.dedupeKey())) {
                previous.delete();
                fail(previous.getId(), BridgeErrorCode.CANCELLED, "replaced by a newer " + interaction.action());
            }
        }
        long now = System.currentTimeMillis();
        QueuedInteraction row = new QueuedInteraction();
        row.setId(nextId(now));
        row.setInstanceId(instanceId);
        row.setAction(interaction.action());
        row.setKind(interaction.kind().name());
        row.setPayload(interaction.payload().toString());
        row.setChatId(interaction.chatId());
        row.setPriority(interaction.kind().priority());
        row.setNotBefore(now + interaction.delay().toMillis());
        row.setCreatedAt(now);
        row.setStatus(InteractionStore.PENDING);
        row.setTextLength(interaction.textLength());
        row.setTypingMs(interaction.typing() == null ? -1 : interaction.typing().toMillis());
        row.setTypingPresence(interaction.typingPresence() == null ? null : interaction.typingPresence().wireValue());
        row.setTimeoutMs(interaction.timeout().toMillis());
        row.setDedupeKey(interaction.dedupeKey());
        // Registered before the row exists, so the worker can never finish it unseen.
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        waiting.put(row.getId(), result);
        row.save();
        wake();
        return result;
    }

    public void wake() {
        Thread current = worker;
        if (current != null) {
            LockSupport.unpark(current);
        }
    }

    public void noteIncoming(String chatId) {
        if (unreadChats.size() >= MAX_TRACKED_UNREAD) {
            unreadChats.clear();
        }
        unreadChats.add(chatId);
    }

    public void noteRead(String chatId) {
        unreadChats.remove(chatId);
    }

    public boolean hasUnread(String chatId) {
        return unreadChats.contains(chatId);
    }

    public List<PendingInteraction> pending() {
        List<PendingInteraction> list = new ArrayList<>();
        for (String status : List.of(InteractionStore.RUNNING, InteractionStore.PENDING)) {
            for (QueuedInteraction row : InteractionStore.withStatus(instanceId, status)) {
                list.add(new PendingInteraction(row.getId(), row.getAction(), row.getChatId(), Instant.ofEpochMilli(row.getNotBefore()),
                        Instant.ofEpochMilli(row.getCreatedAt()), row.getAttempts()));
            }
        }
        return List.copyOf(list);
    }

    public boolean cancel(String id) {
        if (id == null || id.equals(running)) {
            return false;
        }
        QueuedInteraction row = InteractionStore.find(id);
        if (row == null || !instanceId.equals(row.getInstanceId()) || !InteractionStore.PENDING.equals(row.getStatus())) {
            return false;
        }
        row.delete();
        fail(id, BridgeErrorCode.CANCELLED, "cancelled before it ran");
        return true;
    }

    public int clear() {
        int removed = 0;
        List<QueuedInteraction> batch;
        while (!(batch = InteractionStore.withStatus(instanceId, InteractionStore.PENDING)).isEmpty()) {
            for (QueuedInteraction row : batch) {
                row.delete();
                fail(row.getId(), BridgeErrorCode.CANCELLED, "queue cleared");
                removed++;
            }
        }
        return removed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Thread current = worker;
        if (current != null) {
            current.interrupt();
        }
        clear();
        for (QueuedInteraction row : InteractionStore.withStatus(instanceId, InteractionStore.RUNNING)) {
            row.delete();
        }
        for (String id : List.copyOf(waiting.keySet())) {
            fail(id, BridgeErrorCode.QUEUE_CLOSED, "client closed before the interaction ran");
        }
    }

    private void work() {
        recover();
        while (!closed) {
            try {
                if (!gateway.isReady()) {
                    park(NOT_READY_WAIT_MS);
                    continue;
                }
                long now = System.currentTimeMillis();
                PacingOptions limits = pacing.get();
                boolean sendsAllowed = now >= nextSendAt && sends.allows(now, limits.maxSendsPerMinute());
                boolean queriesAllowed = reads.allows(now, limits.maxReadsPerMinute());
                Optional<QueuedInteraction> next = InteractionStore.nextDue(instanceId, now, sendsAllowed, queriesAllowed);
                if (next.isPresent()) {
                    process(next.get(), limits);
                } else {
                    park(waitTime(now, sendsAllowed, queriesAllowed, limits));
                }
            } catch (RuntimeException e) {
                if (closed) {
                    return;
                }
                Console.error("[whatsapp] " + instanceId + ": queue turn failed: " + e, e);
                park(NOT_READY_WAIT_MS);
            }
        }
    }

    // A crash mid-send leaves the row RUNNING: its outcome is unknown, so it is dropped rather than repeated.
    private void recover() {
        for (QueuedInteraction row : InteractionStore.withStatus(instanceId, InteractionStore.RUNNING)) {
            if (Kind.SEND.name().equals(row.getKind())) {
                Console.warn("[whatsapp] %s: dropping %s interrupted by a crash (it may or may not have been sent)", instanceId, row.getAction());
                row.delete();
            } else {
                row.setStatus(InteractionStore.PENDING);
                row.save();
            }
        }
    }

    private long waitTime(long now, boolean sendsAllowed, boolean queriesAllowed, PacingOptions limits) {
        long wait = IDLE_WAIT_MS;
        Optional<QueuedInteraction> future = InteractionStore.earliestAfter(instanceId, now);
        if (future.isPresent()) {
            wait = future.get().getNotBefore() - now;
        }
        if (!sendsAllowed && InteractionStore.hasDue(instanceId, now, Kind.SEND.name())) {
            wait = Math.min(wait, Math.max(nextSendAt, sends.nextFreeAt(now, limits.maxSendsPerMinute())) - now);
        }
        if (!queriesAllowed && InteractionStore.hasDue(instanceId, now, Kind.QUERY.name())) {
            wait = Math.min(wait, reads.nextFreeAt(now, limits.maxReadsPerMinute()) - now);
        }
        return Math.max(MIN_WAIT_MS, Math.min(IDLE_WAIT_MS, wait));
    }

    private void process(QueuedInteraction row, PacingOptions limits) {
        long now = System.currentTimeMillis();
        String id = row.getId();
        if (now - row.getCreatedAt() > limits.maxPendingAge().toMillis()) {
            row.delete();
            fail(id, BridgeErrorCode.EXPIRED, row.getAction() + " waited longer than " + limits.maxPendingAge());
            return;
        }
        CompletableFuture<JsonObject> caller = waiting.get(id);
        if (caller != null && caller.isCancelled()) {
            row.delete();
            waiting.remove(id);
            return;
        }
        row.setStatus(InteractionStore.RUNNING);
        row.save();
        running = id;
        Kind kind = Kind.valueOf(row.getKind());
        JsonObject payload = JsonParser.parseString(row.getPayload()).getAsJsonObject();
        Duration timeout = Duration.ofMillis(row.getTimeoutMs());
        try {
            JsonObject result = kind == Kind.SEND ? send(row, payload, timeout) : call(row.getAction(), payload, timeout);
            row.delete();
            complete(id, result);
            afterSuccess(row, kind, limits);
        } catch (WhatsAppException e) {
            if (retriable(e, kind) && row.getAttempts() + 1 < MAX_ATTEMPTS) {
                row.setStatus(InteractionStore.PENDING);
                row.setAttempts(row.getAttempts() + 1);
                row.setNotBefore(System.currentTimeMillis() + RETRY_DELAY_MS * row.getAttempts());
                row.save();
            } else {
                row.delete();
                failWith(id, e);
            }
        } finally {
            running = null;
        }
    }

    private JsonObject send(QueuedInteraction row, JsonObject payload, Duration timeout) {
        HumanizationOptions signals = humanization.get();
        String chat = row.getChatId();
        if (chat != null && signals.readReceipts() == ReadReceiptMode.ON_REPLY && unreadChats.remove(chat)) {
            JsonObject read = new JsonObject();
            read.addProperty("chatId", chat);
            quietCall("mark_read", read);
        }
        long typing = row.getTypingMs() >= 0
                ? row.getTypingMs()
                : signals.typingDuration(row.getTextLength(), ThreadLocalRandom.current()).toMillis();
        if (chat != null && typing > 0 && row.getTypingPresence() != null) {
            JsonObject presence = new JsonObject();
            presence.addProperty("chatId", chat);
            presence.addProperty("presence", row.getTypingPresence());
            quietCall("send_presence", presence);
            sleep(typing);
        }
        return call(row.getAction(), payload, timeout);
    }

    private void afterSuccess(QueuedInteraction row, Kind kind, PacingOptions limits) {
        long now = System.currentTimeMillis();
        switch (kind) {
            case SEND -> {
                sends.record(now);
                nextSendAt = now + limits.nextGap(ThreadLocalRandom.current()).toMillis();
                HumanizationOptions signals = humanization.get();
                if (!signals.alwaysOnline()) {
                    JsonObject offline = new JsonObject();
                    offline.addProperty("presence", Presence.UNAVAILABLE.wireValue());
                    submit(Interaction.of("send_presence", Kind.PRESENCE, offline, null)
                            .after(signals.onlineHoldAfterSend()).replacing("presence:offline"));
                }
            }
            case QUERY -> reads.record(now);
            case RECEIPT -> {
                if (row.getChatId() != null) {
                    unreadChats.remove(row.getChatId());
                }
            }
            case CALL, PRESENCE, ACTION, KEEPALIVE -> {
            }
        }
    }

    // Retried only when nothing reached WhatsApp, or when repeating is harmless (not a send).
    private static boolean retriable(WhatsAppException e, Kind kind) {
        return switch (e.errorCode()) {
            case NOT_CONNECTED, RATE_LIMITED -> true;
            case CONNECTION_FAILED, TIMEOUT -> kind != Kind.SEND;
            default -> false;
        };
    }

    private JsonObject call(String action, JsonObject payload, Duration timeout) {
        throttle();
        try {
            return gateway.call(action, payload, timeout);
        } finally {
            lastCallAt = System.currentTimeMillis();
        }
    }

    private void quietCall(String action, JsonObject payload) {
        try {
            call(action, payload, Duration.ofSeconds(30));
        } catch (WhatsAppException e) {
            Console.debug("[whatsapp] %s: %s before a send failed: %s", instanceId, action, e.getMessage());
        }
    }

    // The 500 ms floor between any two stanzas, with a little jitter so the rhythm is never exact.
    private void throttle() {
        long interval = pacing.get().interactionInterval().toMillis() + ThreadLocalRandom.current().nextLong(JITTER_MS);
        long wait = lastCallAt + interval - System.currentTimeMillis();
        if (wait > 0) {
            sleep(wait);
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QueueException(instanceId, BridgeErrorCode.QUEUE_CLOSED, "queue stopped");
        }
    }

    private void park(long millis) {
        LockSupport.parkNanos(this, millis * 1_000_000L);
    }

    private void complete(String id, JsonObject result) {
        CompletableFuture<JsonObject> future = waiting.remove(id);
        if (future != null) {
            Threads.VIRTUAL.execute(() -> future.complete(result));
        }
    }

    private void fail(String id, BridgeErrorCode code, String message) {
        failWith(id, new QueueException(instanceId, code, message));
    }

    private void failWith(String id, WhatsAppException error) {
        CompletableFuture<JsonObject> future = waiting.remove(id);
        if (future != null) {
            Threads.VIRTUAL.execute(() -> future.completeExceptionally(error));
        }
    }

    // Millisecond prefix keeps rows in arrival order; the sequence breaks ties inside a millisecond.
    private static String nextId(long now) {
        return String.format("%013d-%09d", now, SEQUENCE.incrementAndGet() % 1_000_000_000L);
    }

    public interface Gateway {

        boolean isReady();

        JsonObject call(String action, JsonObject payload, Duration timeout);
    }
}
