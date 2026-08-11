package com.angatusistemas.whatsapp.protocol;

import java.util.Map;

/**
 * Mirrors the {@code error} object attached to a failed response, or to a
 * standalone {@code error} envelope. {@code code} is one of
 * {@link BridgeErrorCode}'s wire values and is what
 * {@code BridgeExceptionTranslator} uses to pick a concrete exception type.
 */
public record ErrorPayload(String code, String message, Map<String, Object> details) {

    public ErrorPayload {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("error code nao pode ser vazio");
        }
        if (message == null) {
            message = "";
        }
    }
}
