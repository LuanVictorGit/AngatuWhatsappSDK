package com.angatusistemas.whatsapp.request;

import com.angatusistemas.whatsapp.model.Message;
import java.util.Objects;

/**
 * Points at a message already in a chat, for reactions, edits and revokes. participant is only
 * needed in groups, for a message that is not ours.
 *
 * @author Angatu Sistemas
 */
public record MessageRef(String chatId, String messageId, boolean fromMe, String participant) {

    public MessageRef {
        Objects.requireNonNull(chatId, "chatId");
        Objects.requireNonNull(messageId, "messageId");
    }

    public static MessageRef of(Message message) {
        return new MessageRef(message.chatId(), message.messageId(), message.isFromMe(), message.participant());
    }

    public static MessageRef mine(String chatId, String messageId) {
        return new MessageRef(chatId, messageId, true, null);
    }

    public static MessageRef theirs(String chatId, String messageId, String participant) {
        return new MessageRef(chatId, messageId, false, participant);
    }
}
