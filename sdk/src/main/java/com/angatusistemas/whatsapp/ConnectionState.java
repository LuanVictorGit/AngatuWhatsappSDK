package com.angatusistemas.whatsapp;

import java.util.Locale;

/**
 * Where an instance stands with WhatsApp, as reported by the bridge.
 *
 * @author Angatu Sistemas
 */
public enum ConnectionState {
    DISCONNECTED,
    CONNECTING,
    QR_PENDING,
    PAIRING_CODE_PENDING,
    CONNECTED,
    RECONNECTING,
    LOGGED_OUT,
    FAILED;

    public static ConnectionState fromWire(String value) {
        if (value == null) {
            return DISCONNECTED;
        }
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DISCONNECTED;
        }
    }

    public boolean isLoggingIn() {
        return this == QR_PENDING || this == PAIRING_CODE_PENDING;
    }
}
