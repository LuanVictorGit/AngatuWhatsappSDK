package com.angatusistemas.whatsapp.request;

import java.util.Objects;

public final class SendAudioRequest {

    private final String to;
    private final String filePath;
    private final boolean ptt;
    private final String quotedMessageId;

    private SendAudioRequest(Builder builder) {
        this.to = Objects.requireNonNull(builder.to, "to");
        this.filePath = Objects.requireNonNull(builder.filePath, "filePath");
        this.ptt = builder.ptt;
        this.quotedMessageId = builder.quotedMessageId;
    }

    public String to() {
        return to;
    }

    public String filePath() {
        return filePath;
    }

    /** Whether to send as a push-to-talk voice note (waveform bubble) rather than a regular audio file. */
    public boolean ptt() {
        return ptt;
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
        private boolean ptt;
        private String quotedMessageId;

        private Builder() {
        }

        public Builder to(String to) {
            this.to = to;
            return this;
        }

        /** Absolute path, on the machine running the bridge, to the audio file to send. */
        public Builder filePath(String filePath) {
            this.filePath = filePath;
            return this;
        }

        /** Send as a push-to-talk voice note. Default false. */
        public Builder ptt(boolean ptt) {
            this.ptt = ptt;
            return this;
        }

        public Builder quotedMessageId(String quotedMessageId) {
            this.quotedMessageId = quotedMessageId;
            return this;
        }

        public SendAudioRequest build() {
            return new SendAudioRequest(this);
        }
    }
}
