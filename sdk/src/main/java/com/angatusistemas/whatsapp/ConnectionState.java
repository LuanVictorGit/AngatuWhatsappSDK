package com.angatusistemas.whatsapp;

import java.util.Arrays;

/** Mirrors {@code ConnectionStateName} in {@code bridge/src/adapter/BaileysAdapter.ts}. */
public enum ConnectionState {
    DISCONNECTED,
    CONNECTING,
    QR_PENDING,
    PAIRING_CODE_PENDING,
    CONNECTED,
    RECONNECTING,
    LOGGED_OUT,
    FAILED;

    public static ConnectionState fromWireValue(String value) {
        if (value == null) {
            return DISCONNECTED;
        }
        return Arrays.stream(values())
                .filter(state -> state.name().equals(value))
                .findFirst()
                .orElse(DISCONNECTED);
    }
}
