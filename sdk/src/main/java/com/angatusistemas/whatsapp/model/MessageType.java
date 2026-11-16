package com.angatusistemas.whatsapp.model;

import java.util.Locale;

/**
 * Kind of content a message carries, reduced to what an application branches on.
 *
 * @author Angatu Sistemas
 */
public enum MessageType {
    TEXT,
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    STICKER,
    LOCATION,
    CONTACT,
    REACTION,
    POLL,
    INTERACTIVE_REPLY,
    UNKNOWN;

    public static MessageType fromWire(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
