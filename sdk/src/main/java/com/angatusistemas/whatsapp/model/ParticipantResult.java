package com.angatusistemas.whatsapp.model;

/**
 * Outcome of a participant change in a group, per participant; the status is the code WhatsApp
 * answered, 200 when it worked.
 *
 * @author Angatu Sistemas
 */
public record ParticipantResult(String jid, String status) {

    public boolean succeeded() {
        return "200".equals(status);
    }
}
