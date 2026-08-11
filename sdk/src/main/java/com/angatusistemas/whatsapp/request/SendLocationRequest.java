package com.angatusistemas.whatsapp.request;

import java.util.Objects;

public final class SendLocationRequest {

    private final String to;
    private final double latitude;
    private final double longitude;
    private final String name;
    private final String address;
    private final String quotedMessageId;

    private SendLocationRequest(Builder builder) {
        this.to = Objects.requireNonNull(builder.to, "to");
        this.latitude = builder.latitude;
        this.longitude = builder.longitude;
        this.name = builder.name;
        this.address = builder.address;
        this.quotedMessageId = builder.quotedMessageId;
    }

    public String to() {
        return to;
    }

    public double latitude() {
        return latitude;
    }

    public double longitude() {
        return longitude;
    }

    public String name() {
        return name;
    }

    public String address() {
        return address;
    }

    public String quotedMessageId() {
        return quotedMessageId;
    }

    /** Latitude/longitude are required, so they are constructor arguments rather than optional builder setters. */
    public static Builder builder(double latitude, double longitude) {
        return new Builder(latitude, longitude);
    }

    public static final class Builder {
        private final double latitude;
        private final double longitude;
        private String to;
        private String name;
        private String address;
        private String quotedMessageId;

        private Builder(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }

        public Builder to(String to) {
            this.to = to;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder address(String address) {
            this.address = address;
            return this;
        }

        public Builder quotedMessageId(String quotedMessageId) {
            this.quotedMessageId = quotedMessageId;
            return this;
        }

        public SendLocationRequest build() {
            return new SendLocationRequest(this);
        }
    }
}
