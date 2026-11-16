package com.angatusistemas.whatsapp.model;

/**
 * The message another message replies to, as far as the reply itself describes it.
 *
 * @author Angatu Sistemas
 */
public record QuotedMessage(String messageId, String senderId, String text) {
}
