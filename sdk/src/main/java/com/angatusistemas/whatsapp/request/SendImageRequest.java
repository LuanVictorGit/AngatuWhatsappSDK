package com.angatusistemas.whatsapp.request;

import java.util.Objects;

public final class SendImageRequest {

    private final String to;
    private final String filePath;
    private final String caption;
    private final String quotedMessageId;

    private SendImageRequest(Builder builder) {
        this.to = Objects.requireNonNull(builder.to, "to");
        this.filePath = Objects.requireNonNull(builder.filePath, "filePath");
        this.caption = builder.caption;
        this.quotedMessageId = builder.quotedMessageId;
    }

    public String to() {
        return to;
    }

    public String filePath() {
        return filePath;
    }

    public String caption() {
        return caption;
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
        private String caption;
        private String quotedMessageId;

        private Builder() {
        }

        /** Destination chat JID (individual {@code 5511999999999@s.whatsapp.net} or group {@code ...@g.us}). */
        public Builder to(String to) {
            this.to = to;
            return this;
        }

        /** Absolute path, on the machine running the bridge, to the image file to send. */
        public Builder filePath(String filePath) {
            this.filePath = filePath;
            return this;
        }

        public Builder caption(String caption) {
            this.caption = caption;
            return this;
        }

        public Builder quotedMessageId(String quotedMessageId) {
            this.quotedMessageId = quotedMessageId;
            return this;
        }

        public SendImageRequest build() {
            return new SendImageRequest(this);
        }
    }
}
