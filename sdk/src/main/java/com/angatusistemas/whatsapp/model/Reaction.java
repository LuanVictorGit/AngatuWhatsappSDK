package com.angatusistemas.whatsapp.model;

/**
 * An emoji reaction added to, or removed from, a message.
 *
 * @author Angatu Sistemas
 */
public record Reaction(String chatId, String messageId, String senderId, String emoji, boolean removed) {
}
