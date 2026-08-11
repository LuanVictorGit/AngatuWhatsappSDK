package com.angatusistemas.whatsapp.model;

/** The message a {@link Message} is replying to, when it is one Baileys still has in its context info. */
public record QuotedMessage(String messageId, String senderId, String text) {
}
