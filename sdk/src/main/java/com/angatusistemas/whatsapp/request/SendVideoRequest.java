package com.angatusistemas.whatsapp.request;

import java.util.Objects;

public final class SendVideoRequest {

    private final String to;
    private final String filePath;
    private final String caption;
    private final boolean gifPlayback;
    private final String quotedMessageId;

    private SendVideoRequest(Builder builder) {
        this.to = Objects.requireNonNull(builder.to, "to");
        this.filePath = Objects.requireNonNull(builder.filePath, "filePath");
        this.caption = builder.caption;
        this.gifPlayback = builder.gifPlayback;
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

    public boolean gifPlayback() {
        return gifPlayback;
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
        private boolean gifPlayback;
        private String quotedMessageId;

        private Builder() {
        }

        public Builder to(String to) {
            this.to = to;
            return this;
        }

        /** Absolute path, on the machine running the bridge, to the video file to send. */
        public Builder filePath(String filePath) {
            this.filePath = filePath;
            return this;
        }

        public Builder caption(String caption) {
            this.caption = caption;
            return this;
        }

        /** Play back as a looping GIF instead of a regular video. Default false. */
        public Builder gifPlayback(boolean gifPlayback) {
            this.gifPlayback = gifPlayback;
            return this;
        }

        public Builder quotedMessageId(String quotedMessageId) {
            this.quotedMessageId = quotedMessageId;
            return this;
        }

        public SendVideoRequest build() {
            return new SendVideoRequest(this);
        }
    }
}
