package com.angatusistemas.whatsapp.request;

import java.util.Objects;

public final class SendDocumentRequest {

    private final String to;
    private final String filePath;
    private final String fileName;
    private final String mimetype;
    private final String caption;
    private final String quotedMessageId;

    private SendDocumentRequest(Builder builder) {
        this.to = Objects.requireNonNull(builder.to, "to");
        this.filePath = Objects.requireNonNull(builder.filePath, "filePath");
        this.fileName = Objects.requireNonNull(builder.fileName, "fileName");
        this.mimetype = Objects.requireNonNull(builder.mimetype, "mimetype");
        this.caption = builder.caption;
        this.quotedMessageId = builder.quotedMessageId;
    }

    public String to() {
        return to;
    }

    public String filePath() {
        return filePath;
    }

    /** Name shown to the recipient (does not need to match {@link #filePath()}'s actual file name). */
    public String fileName() {
        return fileName;
    }

    /** E.g. {@code application/pdf}. */
    public String mimetype() {
        return mimetype;
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
        private String fileName;
        private String mimetype;
        private String caption;
        private String quotedMessageId;

        private Builder() {
        }

        public Builder to(String to) {
            this.to = to;
            return this;
        }

        /** Absolute path, on the machine running the bridge, to the document file to send. */
        public Builder filePath(String filePath) {
            this.filePath = filePath;
            return this;
        }

        public Builder fileName(String fileName) {
            this.fileName = fileName;
            return this;
        }

        public Builder mimetype(String mimetype) {
            this.mimetype = mimetype;
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

        public SendDocumentRequest build() {
            return new SendDocumentRequest(this);
        }
    }
}
