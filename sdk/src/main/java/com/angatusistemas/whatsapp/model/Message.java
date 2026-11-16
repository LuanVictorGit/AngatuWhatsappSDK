package com.angatusistemas.whatsapp.model;

import java.time.Instant;

/**
 * A message received live by an instance. Only messages newer than the start of the client are
 * delivered; rawJson is filled only when raw data was enabled on the client, because the full
 * protocol message is many times the size of everything else here.
 *
 * @author Angatu Sistemas
 */
public record Message(
        String instanceId,
        String messageId,
        String chatId,
        String chatIdLid,
        String senderId,
        String participant,
        Instant timestamp,
        String text,
        MessageType type,
        boolean isGroup,
        boolean isFromMe,
        String pushName,
        QuotedMessage quotedMessage,
        ButtonReply buttonReply,
        boolean hasMedia,
        String mimetype,
        String rawJson) {

    public boolean hasText() {
        return text != null && !text.isBlank();
    }
}
