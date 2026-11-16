package com.angatusistemas.whatsapp.model;

import java.util.Optional;

/**
 * A contact as WhatsApp describes it on request; each lookup costs three queries.
 *
 * @author Angatu Sistemas
 */
public record Contact(String jid, String status, String profilePictureUrl, boolean onWhatsApp) {

    public Optional<String> profilePicture() {
        return Optional.ofNullable(profilePictureUrl);
    }
}
