package com.angatusistemas.whatsapp.model;

/**
 * Whether a number has WhatsApp and the canonical JID to send to; cached tells the answer came from
 * the bridge cache instead of a new query.
 *
 * @author Angatu Sistemas
 */
public record NumberCheck(String jid, boolean exists, boolean cached) {
}
