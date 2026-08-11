package com.angatusistemas.whatsapp;

import java.util.Arrays;

/**
 * Why a connection ended. The first five mirror {@code DisconnectCause} in
 * {@code bridge/src/adapter/BaileysAdapter.ts} - a bridge-reported cause
 * about the WhatsApp/Baileys connection itself. {@link #BRIDGE_CRASH} is
 * Java-side only: it means the bridge OS process died, which is not
 * something the bridge could ever report about itself.
 */
public enum DisconnectCause {
    /** The user (or another device) logged the session out; requires a new QR/pairing code. */
    USER_LOGOUT,
    /** Transient network/socket failure; the bridge already retries this automatically. */
    CONNECTION_LOST,
    /** WhatsApp asked the client to restart the connection; the bridge retries immediately. */
    WHATSAPP_RESTART,
    /** Credentials rejected (bad/expired session, banned number); requires a new QR/pairing code. */
    AUTH_FAILURE,
    /** The bridge OS process terminated unexpectedly. */
    BRIDGE_CRASH,
    /** Cause not recognized by this SDK version, or not applicable. */
    UNKNOWN;

    public static DisconnectCause fromWireValue(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        return Arrays.stream(values())
                .filter(cause -> cause.name().equals(value))
                .findFirst()
                .orElse(UNKNOWN);
    }
}
