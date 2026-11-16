package com.angatusistemas.whatsapp.request;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A document read from a file on this machine; fileName is what the recipient sees.
 *
 * @author Angatu Sistemas
 */
public record SendDocumentRequest(String to, Path file, String fileName, String mimetype, String caption, String quotedMessageId) {

    public SendDocumentRequest {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(mimetype, "mimetype");
        if (fileName == null || fileName.isBlank()) {
            Path name = file.getFileName();
            fileName = name == null ? "document" : name.toString();
        }
    }

    public static SendDocumentRequest of(String to, Path file, String mimetype) {
        return new SendDocumentRequest(to, file, null, mimetype, null, null);
    }

    public SendDocumentRequest withFileName(String name) {
        return new SendDocumentRequest(to, file, name, mimetype, caption, quotedMessageId);
    }

    public SendDocumentRequest withCaption(String text) {
        return new SendDocumentRequest(to, file, fileName, mimetype, text, quotedMessageId);
    }

    public SendDocumentRequest quoting(String messageId) {
        return new SendDocumentRequest(to, file, fileName, mimetype, caption, messageId);
    }
}
