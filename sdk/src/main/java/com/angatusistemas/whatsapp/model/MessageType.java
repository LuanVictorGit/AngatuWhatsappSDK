package com.angatusistemas.whatsapp.model;

import java.util.Arrays;

/** Mirrors the abstract type strings {@code bridge/src/adapter/MessageMapper.ts} maps Baileys content types to. */
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
    PROTOCOL,
    /** A content type this SDK version does not (yet) map to a specific type. */
    UNKNOWN;

    public static MessageType fromWireValue(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        return Arrays.stream(values())
                .filter(type -> type.name().equals(value))
                .findFirst()
                .orElse(UNKNOWN);
    }
}
