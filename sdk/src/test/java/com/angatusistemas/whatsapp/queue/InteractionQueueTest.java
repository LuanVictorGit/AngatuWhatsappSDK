package com.angatusistemas.whatsapp.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.angatusistemas.whatsapp.exception.BridgeErrorCode;
import com.angatusistemas.whatsapp.exception.ConnectionException;
import com.angatusistemas.whatsapp.exception.QueueException;
import com.angatusistemas.whatsapp.exception.TimeoutException;
import com.angatusistemas.whatsapp.exception.WhatsAppException;
import com.angatusistemas.whatsapp.model.Presence;
import com.angatusistemas.whatsapp.options.HumanizationOptions;
import com.angatusistemas.whatsapp.options.PacingOptions;
import com.angatusistemas.whatsapp.options.ReadReceiptMode;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The interaction queue against a fake bridge: spacing, order, typing, receipts, retries, expiry
 * and shutdown, with rows really stored in the SQLite test database.
 *
 * @author Angatu Sistemas
 */
class InteractionQueueTest {

    private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    private final List<InteractionQueue> queues = new ArrayList<>();
    private PacingOptions pacing = PacingOptions.defaults().withGap(Duration.ZERO, Duration.ZERO).withMaxSendsPerMinute(100);
    private HumanizationOptions humanization = HumanizationOptions.off();

    @AfterEach
    void closeQueues() {
        queues.forEach(InteractionQueue::close);
    }

    @Test
    void keepsAtLeastFiveHundredMillisecondsBetweenInteractions() throws Exception {
        InteractionQueue queue = queue(call -> ok());
        List<CompletableFuture<JsonObject>> results = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            results.add(queue.submit(Interaction.of("get_me", Kind.ACTION, new JsonObject(), null)));
        }
        CompletableFuture.allOf(results.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        assertEquals(4, calls.size());
        for (int i = 1; i < calls.size(); i++) {
            long gap = calls.get(i).at() - calls.get(i - 1).at();
            assertTrue(gap >= 495, "gap between interactions was " + gap + " ms");
        }
    }

    @Test
    void runsHigherPriorityFirstAndKeepsArrivalOrderWithinOne() throws Exception {
        InteractionQueue queue = queue(call -> ok());
        CompletableFuture<JsonObject> blocker = queue.submit(Interaction.of("first", Kind.QUERY, new JsonObject(), null));
        blocker.get(5, TimeUnit.SECONDS);
        calls.clear();
        // All four become due together, so only priority and arrival decide the order.
        Duration together = Duration.ofMillis(400);
        CompletableFuture<JsonObject> query = queue.submit(Interaction.of("query", Kind.QUERY, new JsonObject(), null).after(together));
        CompletableFuture<JsonObject> action1 = queue.submit(Interaction.of("action-1", Kind.ACTION, new JsonObject(), null).after(together));
        CompletableFuture<JsonObject> action2 = queue.submit(Interaction.of("action-2", Kind.ACTION, new JsonObject(), null).after(together));
        CompletableFuture<JsonObject> reject = queue.submit(Interaction.of("reject_call", Kind.CALL, new JsonObject(), null).after(together));
        CompletableFuture.allOf(query, action1, action2, reject).get(10, TimeUnit.SECONDS);
        assertEquals(List.of("reject_call", "action-1", "action-2", "query"), calls.stream().map(Call::action).toList());
    }

    @Test
    void showsTypingThenSendsAndMarksTheChatReadWhenReplying() throws Exception {
        humanization = HumanizationOptions.off().withReadReceipts(ReadReceiptMode.ON_REPLY);
        InteractionQueue queue = queue(call -> ok());
        queue.noteIncoming("5511999990000@s.whatsapp.net");
        JsonObject payload = new JsonObject();
        payload.addProperty("to", "5511999990000@s.whatsapp.net");
        queue.submit(Interaction.send("send_text", payload, "5511999990000@s.whatsapp.net", 5, Duration.ofMillis(700), Presence.COMPOSING))
                .get(10, TimeUnit.SECONDS);
        assertEquals(List.of("mark_read", "send_presence", "send_text"), calls.stream().map(Call::action).toList());
        long typed = calls.get(2).at() - calls.get(1).at();
        assertTrue(typed >= 690, "typing lasted " + typed + " ms");
        assertFalse(queue.hasUnread("5511999990000@s.whatsapp.net"));
    }

    @Test
    void spacesSendsByTheConfiguredGap() throws Exception {
        pacing = pacing.withGap(Duration.ofMillis(1_500), Duration.ofMillis(1_500));
        InteractionQueue queue = queue(call -> ok());
        JsonObject payload = new JsonObject();
        CompletableFuture<JsonObject> first = queue.submit(Interaction.send("send_text", payload, null, 1, Duration.ZERO, null));
        CompletableFuture<JsonObject> second = queue.submit(Interaction.send("send_text", payload, null, 1, Duration.ZERO, null));
        CompletableFuture.allOf(first, second).get(10, TimeUnit.SECONDS);
        List<Call> sent = calls.stream().filter(call -> call.action().equals("send_text")).toList();
        long gap = sent.get(1).at() - sent.get(0).at();
        assertTrue(gap >= 1_450, "sends were " + gap + " ms apart");
    }

    @Test
    void retriesWhatNeverReachedWhatsApp() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        InteractionQueue queue = queue(call -> {
            if (attempts.incrementAndGet() == 1) {
                throw new ConnectionException("test", BridgeErrorCode.NOT_CONNECTED, "not yet");
            }
            return ok();
        });
        queue.submit(Interaction.of("get_me", Kind.ACTION, new JsonObject(), null)).get(15, TimeUnit.SECONDS);
        assertEquals(2, attempts.get());
    }

    @Test
    void neverRepeatsASendWhoseOutcomeIsUnknown() {
        InteractionQueue queue = queue(call -> {
            throw new TimeoutException("test", BridgeErrorCode.TIMEOUT, "no answer");
        });
        CompletableFuture<JsonObject> send = queue.submit(Interaction.send("send_text", new JsonObject(), null, 1, Duration.ZERO, null));
        ExecutionException failure = assertThrows(ExecutionException.class, () -> send.get(10, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, failure.getCause());
        assertEquals(1, calls.size());
    }

    @Test
    void replacesAPendingInteractionWithTheSameKey() throws Exception {
        InteractionQueue queue = queue(call -> ok());
        CompletableFuture<JsonObject> old = queue.submit(Interaction.of("send_presence", Kind.PRESENCE, new JsonObject(), null)
                .after(Duration.ofSeconds(30)).replacing("presence:offline"));
        CompletableFuture<JsonObject> latest = queue.submit(Interaction.of("send_presence", Kind.PRESENCE, new JsonObject(), null)
                .replacing("presence:offline"));
        latest.get(5, TimeUnit.SECONDS);
        ExecutionException failure = assertThrows(ExecutionException.class, () -> old.get(1, TimeUnit.SECONDS));
        assertEquals(BridgeErrorCode.CANCELLED, ((WhatsAppException) failure.getCause()).errorCode());
        assertEquals(1, calls.size());
    }

    @Test
    void cancelsAPendingInteraction() {
        InteractionQueue queue = queue(call -> ok());
        CompletableFuture<JsonObject> later = queue.submit(Interaction.of("get_me", Kind.ACTION, new JsonObject(), null).after(Duration.ofSeconds(30)));
        String id = queue.pending().getFirst().id();
        assertTrue(queue.cancel(id));
        assertThrows(ExecutionException.class, () -> later.get(2, TimeUnit.SECONDS));
        assertTrue(queue.pending().isEmpty());
    }

    @Test
    void failsWaitingCallersAndDropsRowsOnClose() {
        InteractionQueue queue = queue(call -> ok());
        CompletableFuture<JsonObject> later = queue.submit(Interaction.of("get_me", Kind.ACTION, new JsonObject(), null).after(Duration.ofSeconds(30)));
        queue.close();
        ExecutionException failure = assertThrows(ExecutionException.class, () -> later.get(2, TimeUnit.SECONDS));
        assertInstanceOf(QueueException.class, failure.getCause());
        assertTrue(queue.pending().isEmpty());
    }

    private InteractionQueue queue(Function<Call, JsonObject> bridge) {
        String instance = "test-" + UUID.randomUUID();
        InteractionQueue queue = new InteractionQueue(instance, new InteractionQueue.Gateway() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public JsonObject call(String action, JsonObject payload, Duration timeout) {
                Call call = new Call(action, System.currentTimeMillis());
                calls.add(call);
                return bridge.apply(call);
            }
        }, () -> pacing, () -> humanization);
        queues.add(queue);
        queue.start();
        return queue;
    }

    private static JsonObject ok() {
        JsonObject result = new JsonObject();
        result.addProperty("messageId", "ID");
        return result;
    }

    private record Call(String action, long at) {
    }
}
