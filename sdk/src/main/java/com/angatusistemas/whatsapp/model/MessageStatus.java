package com.angatusistemas.whatsapp.model;

import java.util.Locale;

/**
 * Delivery progress of a message this account sent.
 *
 * @author Angatu Sistemas
 */
public enum MessageStatus {
    ERROR,
    PENDING,
    SERVER_ACK,
    DELIVERED,
    READ,
    PLAYED;

    public static MessageStatus fromWire(String value) {
        if (value == null) {
            return null;
        }
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
