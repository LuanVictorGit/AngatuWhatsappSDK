package com.angatusistemas.whatsapp.request;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A video read from a file on this machine; gifPlayback sends it as a looping GIF.
 *
 * @author Angatu Sistemas
 */
public record SendVideoRequest(String to, Path file, String caption, boolean gifPlayback, String quotedMessageId) {

    public SendVideoRequest {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(file, "file");
    }

    public static SendVideoRequest of(String to, Path file) {
        return new SendVideoRequest(to, file, null, false, null);
    }

    public SendVideoRequest withCaption(String text) {
        return new SendVideoRequest(to, file, text, gifPlayback, quotedMessageId);
    }

    public SendVideoRequest asGif() {
        return new SendVideoRequest(to, file, caption, true, quotedMessageId);
    }

    public SendVideoRequest quoting(String messageId) {
        return new SendVideoRequest(to, file, caption, gifPlayback, messageId);
    }
}
