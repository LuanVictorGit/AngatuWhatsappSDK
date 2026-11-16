package com.angatusistemas.whatsapp.request;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A sticker read from a WebP file on this machine.
 *
 * @author Angatu Sistemas
 */
public record SendStickerRequest(String to, Path file, String quotedMessageId) {

    public SendStickerRequest {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(file, "file");
    }

    public static SendStickerRequest of(String to, Path file) {
        return new SendStickerRequest(to, file, null);
    }

    public SendStickerRequest quoting(String messageId) {
        return new SendStickerRequest(to, file, messageId);
    }
}
