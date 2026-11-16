package com.angatusistemas.whatsapp.request;

import java.time.Duration;
import java.util.Objects;

/**
 * A text message; typing overrides how long "typing..." shows before it (null computes it from the
 * text length), and quotedMessageId makes it a reply.
 *
 * @author Angatu Sistemas
 */
public record SendTextRequest(String to, String text, String quotedMessageId, Duration typing) {

    public SendTextRequest {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        if (typing != null && typing.isNegative()) {
            throw new IllegalArgumentException("typing must not be negative");
        }
    }

    public static SendTextRequest of(String to, String text) {
        return new SendTextRequest(to, text, null, null);
    }

    public SendTextRequest quoting(String messageId) {
        return new SendTextRequest(to, text, messageId, typing);
    }

    public SendTextRequest withTyping(Duration duration) {
        return new SendTextRequest(to, text, quotedMessageId, duration);
    }
}
