package com.angatusistemas.whatsapp.request;

import java.nio.file.Path;
import java.util.Objects;

/**
 * An image read from a file on this machine (the bridge process reads it), with an optional caption.
 *
 * @author Angatu Sistemas
 */
public record SendImageRequest(String to, Path file, String caption, String quotedMessageId) {

    public SendImageRequest {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(file, "file");
    }

    public static SendImageRequest of(String to, Path file) {
        return new SendImageRequest(to, file, null, null);
    }

    public SendImageRequest withCaption(String text) {
        return new SendImageRequest(to, file, text, quotedMessageId);
    }

    public SendImageRequest quoting(String messageId) {
        return new SendImageRequest(to, file, caption, messageId);
    }
}
