package com.angatusistemas.whatsapp.request;

import java.util.Objects;

/**
 * Replacement text for a message that was already delivered.
 *
 * <p>WhatsApp accepts an edit only for a message the connected account sent
 * and only inside its own edit window - roughly fifteen minutes, decided
 * server-side and not published anywhere. There is no way to ask beforehand
 * whether a given message is still editable: past the window the server
 * simply refuses, and that refusal is what
 * {@link com.angatusistemas.whatsapp.WhatsAppClient#editMessage} reports.
 */
public final class EditMessageRequest {

    private final String chatId;
    private final String messageId;
    private final String text;
    private final boolean fromMe;
    private final String participant;

    private EditMessageRequest(Builder builder) {
        this.chatId = Objects.requireNonNull(builder.chatId, "chatId");
        this.messageId = Objects.requireNonNull(builder.messageId, "messageId");
        this.text = Objects.requireNonNull(builder.text, "text");
        this.fromMe = builder.fromMe;
        this.participant = builder.participant;
    }

    public String chatId() {
        return chatId;
    }

    public String messageId() {
        return messageId;
    }

    /** The new body of the message. */
    public String text() {
        return text;
    }

    /** Whether the message being edited was sent by this instance. Default true. */
    public boolean fromMe() {
        return fromMe;
    }

    /** Sender of the target message, when {@code chatId} is a group and {@link #fromMe()} is false. */
    public String participant() {
        return participant;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String chatId;
        private String messageId;
        private String text;
        private boolean fromMe = true;
        private String participant;

        private Builder() {
        }

        public Builder chatId(String chatId) {
            this.chatId = chatId;
            return this;
        }

        public Builder messageId(String messageId) {
            this.messageId = messageId;
            return this;
        }

        public Builder text(String text) {
            this.text = text;
            return this;
        }

        public Builder fromMe(boolean fromMe) {
            this.fromMe = fromMe;
            return this;
        }

        public Builder participant(String participant) {
            this.participant = participant;
            return this;
        }

        public EditMessageRequest build() {
            return new EditMessageRequest(this);
        }
    }
}
