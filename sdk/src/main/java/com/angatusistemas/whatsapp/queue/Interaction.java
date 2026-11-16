package com.angatusistemas.whatsapp.queue;

import com.angatusistemas.whatsapp.model.Presence;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.Objects;

/**
 * An operation to enqueue: the bridge action and its payload, the chat it touches, and for sends
 * how long to show "typing" (null computes it from textLength) and which presence to show.
 *
 * @author Angatu Sistemas
 */
public record Interaction(
        String action,
        Kind kind,
        JsonObject payload,
        String chatId,
        int textLength,
        Duration typing,
        Presence typingPresence,
        Duration delay,
        Duration timeout,
        String dedupeKey) {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(120);

    public Interaction {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(kind, "kind");
        payload = payload == null ? new JsonObject() : payload;
        delay = delay == null ? Duration.ZERO : delay;
        timeout = timeout == null ? (kind == Kind.SEND ? SEND_TIMEOUT : DEFAULT_TIMEOUT) : timeout;
    }

    public static Interaction of(String action, Kind kind, JsonObject payload, String chatId) {
        return new Interaction(action, kind, payload, chatId, 0, null, null, null, null, null);
    }

    public static Interaction send(String action, JsonObject payload, String chatId, int textLength, Duration typing, Presence presence) {
        return new Interaction(action, Kind.SEND, payload, chatId, textLength, typing, presence, null, null, null);
    }

    public Interaction after(Duration wait) {
        return new Interaction(action, kind, payload, chatId, textLength, typing, typingPresence, wait, timeout, dedupeKey);
    }

    public Interaction withTimeout(Duration limit) {
        return new Interaction(action, kind, payload, chatId, textLength, typing, typingPresence, delay, limit, dedupeKey);
    }

    public Interaction replacing(String key) {
        return new Interaction(action, kind, payload, chatId, textLength, typing, typingPresence, delay, timeout, key);
    }
}
