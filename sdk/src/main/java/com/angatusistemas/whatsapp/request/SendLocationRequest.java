package com.angatusistemas.whatsapp.request;

import java.util.Objects;

/**
 * A map pin, optionally named and with an address line.
 *
 * @author Angatu Sistemas
 */
public record SendLocationRequest(String to, double latitude, double longitude, String name, String address, String quotedMessageId) {

    public SendLocationRequest {
        Objects.requireNonNull(to, "to");
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("coordinates out of range");
        }
    }

    public static SendLocationRequest of(String to, double latitude, double longitude) {
        return new SendLocationRequest(to, latitude, longitude, null, null, null);
    }

    public SendLocationRequest withName(String placeName, String placeAddress) {
        return new SendLocationRequest(to, latitude, longitude, placeName, placeAddress, quotedMessageId);
    }

    public SendLocationRequest quoting(String messageId) {
        return new SendLocationRequest(to, latitude, longitude, name, address, messageId);
    }
}
