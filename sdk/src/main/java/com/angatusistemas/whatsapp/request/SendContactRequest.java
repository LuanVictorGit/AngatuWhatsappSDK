package com.angatusistemas.whatsapp.request;

import java.util.Objects;

public final class SendContactRequest {

    private final String to;
    private final String displayName;
    private final String phoneNumber;
    private final String organization;
    private final String quotedMessageId;

    private SendContactRequest(Builder builder) {
        this.to = Objects.requireNonNull(builder.to, "to");
        this.displayName = Objects.requireNonNull(builder.displayName, "displayName");
        this.phoneNumber = Objects.requireNonNull(builder.phoneNumber, "phoneNumber");
        this.organization = builder.organization;
        this.quotedMessageId = builder.quotedMessageId;
    }

    public String to() {
        return to;
    }

    public String displayName() {
        return displayName;
    }

    public String phoneNumber() {
        return phoneNumber;
    }

    public String organization() {
        return organization;
    }

    public String quotedMessageId() {
        return quotedMessageId;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String to;
        private String displayName;
        private String phoneNumber;
        private String organization;
        private String quotedMessageId;

        private Builder() {
        }

        public Builder to(String to) {
            this.to = to;
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder phoneNumber(String phoneNumber) {
            this.phoneNumber = phoneNumber;
            return this;
        }

        public Builder organization(String organization) {
            this.organization = organization;
            return this;
        }

        public Builder quotedMessageId(String quotedMessageId) {
            this.quotedMessageId = quotedMessageId;
            return this;
        }

        public SendContactRequest build() {
            return new SendContactRequest(this);
        }
    }
}
