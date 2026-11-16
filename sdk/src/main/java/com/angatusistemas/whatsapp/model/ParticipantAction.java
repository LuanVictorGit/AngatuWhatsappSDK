package com.angatusistemas.whatsapp.model;

import java.util.Locale;

/**
 * What to do with members of a group.
 *
 * @author Angatu Sistemas
 */
public enum ParticipantAction {
    ADD,
    REMOVE,
    PROMOTE,
    DEMOTE;

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
