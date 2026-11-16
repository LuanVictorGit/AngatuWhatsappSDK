package com.angatusistemas.whatsapp.model;

import java.util.Locale;

/**
 * Presence states, both the ones this account publishes and the ones contacts show.
 *
 * @author Angatu Sistemas
 */
public enum Presence {
    AVAILABLE,
    UNAVAILABLE,
    COMPOSING,
    RECORDING,
    PAUSED;

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Presence fromWire(String value) {
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
