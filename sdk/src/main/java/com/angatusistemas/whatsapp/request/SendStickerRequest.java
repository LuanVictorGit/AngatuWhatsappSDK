package com.angatusistemas.whatsapp.request;

import java.util.Objects;

public final class SendStickerRequest {

    private final String to;
    private final String filePath;
    private final String quotedMessageId;

    private SendStickerRequest(Builder builder) {
        this.to = Objects.requireNonNull(builder.to, "to");
        this.filePath = Objects.requireNonNull(builder.filePath, "filePath");
        this.quotedMessageId = builder.quotedMessageId;
    }

    public String to() {
        return to;
    }

    public String filePath() {
        return filePath;
    }

    public String quotedMessageId() {
        return quotedMessageId;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String to;
        private String filePath;
        private String quotedMessageId;

        private Builder() {
        }

        public Builder to(String to) {
            this.to = to;
            return this;
        }

        /** Absolute path, on the machine running the bridge, to the sticker file (webp) to send. */
        public Builder filePath(String filePath) {
            this.filePath = filePath;
            return this;
        }

        public Builder quotedMessageId(String quotedMessageId) {
            this.quotedMessageId = quotedMessageId;
            return this;
        }

        public SendStickerRequest build() {
            return new SendStickerRequest(this);
        }
    }
}
