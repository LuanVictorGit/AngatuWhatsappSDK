package com.angatusistemas.whatsapp.model;

/**
 * A contact chose one of the buttons this account sent: which one, on which message, and the reply
 * message that carried the choice.
 *
 * @author Angatu Sistemas
 */
public record ButtonClick(String buttonId, String label, String menuMessageId, Message reply) {

    public String chatId() {
        return reply.chatId();
    }

    public String senderId() {
        return reply.senderId();
    }
}
