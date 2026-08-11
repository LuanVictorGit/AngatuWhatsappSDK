package com.angatusistemas.whatsapp.protocol;

import java.util.Arrays;

/**
 * Mirrors {@code bridge/src/protocol/errors.ts} ErrorCode one-to-one. Keep
 * both lists in sync when adding a new code on either side.
 */
public enum BridgeErrorCode {
    INVALID_TOKEN,
    PROTOCOL_VERSION_MISMATCH,
    INVALID_MESSAGE,
    UNKNOWN_ACTION,
    INSTANCE_NOT_FOUND,
    INSTANCE_ALREADY_EXISTS,
    NOT_CONNECTED,
    CONNECTION_FAILED,
    AUTH_FAILED,
    SEND_FAILED,
    TIMEOUT,
    UNSUPPORTED_FEATURE,
    MEDIA_NOT_FOUND,
    INVALID_PAYLOAD,
    INTERNAL_ERROR,
    /** Java-side fallback for a wire code this SDK version does not (yet) recognize. */
    UNKNOWN;

    public static BridgeErrorCode fromWireValue(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        return Arrays.stream(values())
                .filter(code -> code.name().equals(value))
                .findFirst()
                .orElse(UNKNOWN);
    }
}
