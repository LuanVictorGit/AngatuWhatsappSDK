package com.angatusistemas.whatsapp.request;

import java.nio.file.Path;
import java.util.Objects;

/**
 * An audio file; voiceNote sends it as a voice note (OGG/Opus expected), preceded by
 * "recording audio..." instead of "typing...".
 *
 * @author Angatu Sistemas
 */
public record SendAudioRequest(String to, Path file, boolean voiceNote, String quotedMessageId) {

    public SendAudioRequest {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(file, "file");
    }

    public static SendAudioRequest of(String to, Path file) {
        return new SendAudioRequest(to, file, false, null);
    }

    public static SendAudioRequest voiceNote(String to, Path file) {
        return new SendAudioRequest(to, file, true, null);
    }

    public SendAudioRequest quoting(String messageId) {
        return new SendAudioRequest(to, file, voiceNote, messageId);
    }
}
