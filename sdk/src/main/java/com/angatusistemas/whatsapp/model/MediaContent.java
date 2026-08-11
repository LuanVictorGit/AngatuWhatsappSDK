package com.angatusistemas.whatsapp.model;

import java.util.Arrays;

/** Result of {@code WhatsAppClient.downloadMedia(String, String)}: the decoded bytes plus their MIME type. */
public record MediaContent(byte[] data, String mimetype) {

    // Records generate reference-based equals/hashCode for array components by
    // default; overridden here so two MediaContent instances with equal bytes
    // compare equal, and so toString() does not dump raw bytes as noise.

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MediaContent that)) {
            return false;
        }
        return Arrays.equals(data, that.data) && mimetype.equals(that.mimetype);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(data) + mimetype.hashCode();
    }

    @Override
    public String toString() {
        return "MediaContent[data=" + data.length + " bytes, mimetype=" + mimetype + "]";
    }
}
