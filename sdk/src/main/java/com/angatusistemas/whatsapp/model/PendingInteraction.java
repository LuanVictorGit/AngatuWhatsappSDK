package com.angatusistemas.whatsapp.model;

import java.time.Instant;

/**
 * An interaction still waiting in the queue: what it does, to which chat, and from when it may run.
 *
 * @author Angatu Sistemas
 */
public record PendingInteraction(String id, String action, String chatId, Instant notBefore, Instant createdAt, int attempts) {
}
