package com.angatusistemas.whatsapp.model;

/**
 * Which button a contact pressed on an interactive message, as carried by the reply.
 *
 * @author Angatu Sistemas
 */
public record ButtonReply(String buttonId, String text, String sourceMessageId) {
}
