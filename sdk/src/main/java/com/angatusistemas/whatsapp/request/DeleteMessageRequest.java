package com.angatusistemas.whatsapp.request;

import java.util.Objects;

/**
 * Revokes ("delete for everyone") a message that was already delivered.
 *
 * <p>Same window as {@link EditMessageRequest}: WhatsApp only honours the
 * revoke for a while after delivery, and the limit is the server's, not
 * ours. In a one-to-one chat the message has to be ours; in a group, an
 * administrator may also revoke another member's - which is what
 * {@link #participant()} identifies.
 *
 * <p>Deleting only on our own side is not offered on purpose: that is a
 * decision about what a local history shows, and belongs to whoever keeps
 * that history, not to WhatsApp.
 */
public final class DeleteMessageRequest {

    private final String chatId;
    private final String messageId;
    private final boolean fromMe;
    private final String participant;

    private DeleteMessageRequest(Builder builder) {
        this.chatId = Objects.requireNonNull(builder.chatId, "chatId");
        this.messageId = Objects.requireNonNull(builder.messageId, "messageId");
        this.fromMe = builder.fromMe;
        this.participant = builder.participant;
    }

    public String chatId() {
        return chatId;
    }

    public String messageId() {
        return messageId;
    }

    /** Whether the message being revoked was sent by this instance. Default true. */
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

        public Builder fromMe(boolean fromMe) {
            this.fromMe = fromMe;
            return this;
        }

        public Builder participant(String participant) {
            this.participant = participant;
            return this;
        }

        public DeleteMessageRequest build() {
            return new DeleteMessageRequest(this);
        }
    }
}
