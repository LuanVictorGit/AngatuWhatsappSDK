package com.angatusistemas.whatsapp.model;

import java.time.Instant;

/**
 * A presence change of a contact in a chat this account subscribed to: online, typing, recording.
 *
 * @author Angatu Sistemas
 */
public record PresenceUpdate(String chatId, String participant, Presence presence, Instant lastSeen) {
}
