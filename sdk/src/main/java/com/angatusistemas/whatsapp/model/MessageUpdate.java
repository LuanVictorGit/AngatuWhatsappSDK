package com.angatusistemas.whatsapp.model;

/**
 * A change to a message already in a chat: a new delivery status, or new text after an edit.
 *
 * @author Angatu Sistemas
 */
public record MessageUpdate(String chatId, String messageId, boolean fromMe, MessageStatus status, String editedText) {
}
