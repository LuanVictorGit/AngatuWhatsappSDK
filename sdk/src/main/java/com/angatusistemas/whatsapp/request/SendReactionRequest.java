package com.angatusistemas.whatsapp.request;

import java.util.Objects;

public final class SendReactionRequest {

    private final String chatId;
    private final String messageId;
    private final String emoji;
    private final boolean fromMe;
    private final String participant;

    private SendReactionRequest(Builder builder) {
        this.chatId = Objects.requireNonNull(builder.chatId, "chatId");
        this.messageId = Objects.requireNonNull(builder.messageId, "messageId");
        this.emoji = builder.emoji;
        this.fromMe = builder.fromMe;
        this.participant = builder.participant;
    }

    public String chatId() {
        return chatId;
    }

    public String messageId() {
        return messageId;
    }

    /** The reaction emoji, or null/empty to remove a previously sent reaction. */
    public String emoji() {
        return emoji;
    }

    /** Whether the message being reacted to was sent by this instance. Default false. */
    public boolean fromMe() {
        return fromMe;
    }

    /** Sender of the message being reacted to, when {@code chatId} is a group and {@link #fromMe()} is false. */
    public String participant() {
        return participant;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String chatId;
        private String messageId;
        private String emoji;
        private boolean fromMe;
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

        public Builder emoji(String emoji) {
            this.emoji = emoji;
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

        public SendReactionRequest build() {
            return new SendReactionRequest(this);
        }
    }
}
