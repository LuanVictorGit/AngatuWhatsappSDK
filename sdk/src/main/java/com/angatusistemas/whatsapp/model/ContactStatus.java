package com.angatusistemas.whatsapp.model;

import java.time.Instant;

/**
 * The "about" text a contact shows, and when it was set; both null when hidden from this account.
 *
 * @author Angatu Sistemas
 */
public record ContactStatus(String jid, String status, Instant setAt) {
}
