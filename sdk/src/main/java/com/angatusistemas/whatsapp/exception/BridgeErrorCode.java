package com.angatusistemas.whatsapp.exception;

import java.util.Locale;

/**
 * Machine-readable failure codes, mirrored one to one by the bridge's ErrorCode, plus the codes the
 * Java side raises on its own (queue closed, interaction expired).
 *
 * @author Angatu Sistemas
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
    RATE_LIMITED,
    UNSUPPORTED_FEATURE,
    MEDIA_NOT_FOUND,
    INVALID_PAYLOAD,
    INTERNAL_ERROR,
    QUEUE_CLOSED,
    EXPIRED,
    CANCELLED;

    public static BridgeErrorCode fromWire(String value) {
        if (value == null) {
            return INTERNAL_ERROR;
        }
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return INTERNAL_ERROR;
        }
    }
}
