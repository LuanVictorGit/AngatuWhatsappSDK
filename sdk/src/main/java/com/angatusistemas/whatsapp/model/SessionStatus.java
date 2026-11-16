package com.angatusistemas.whatsapp.model;

/**
 * What the credentials on disk say about a session before connecting: whether it resumes without a
 * QR code, and which number it belongs to.
 *
 * @author Angatu Sistemas
 */
public record SessionStatus(State state, String phoneNumber, boolean backupAvailable) {

    public boolean canResume() {
        return state == State.PAIRED;
    }

    public enum State {
        NOT_PAIRED,
        PAIRED,
        PAIRING_INCOMPLETE,
        CORRUPTED
    }
}
