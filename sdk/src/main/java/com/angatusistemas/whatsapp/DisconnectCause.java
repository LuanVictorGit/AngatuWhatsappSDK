package com.angatusistemas.whatsapp;

import java.util.Locale;

/**
 * Why a connection ended: a deliberate logout, a network drop, WhatsApp asking for a restart,
 * revoked credentials, another client taking the session, a login nobody completed, or the local
 * bridge process dying.
 *
 * @author Angatu Sistemas
 */
public enum DisconnectCause {
    USER_LOGOUT,
    CONNECTION_LOST,
    WHATSAPP_RESTART,
    AUTH_FAILURE,
    CONNECTION_REPLACED,
    LOGIN_TIMEOUT,
    BRIDGE_CRASH,
    UNKNOWN;

    public static DisconnectCause fromWire(String value) {
        if (value == null) {
            return null;
        }
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
