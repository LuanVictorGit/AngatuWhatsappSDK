package com.angatusistemas.whatsapp.model;

import java.util.List;

/**
 * The public profile of a WhatsApp Business account.
 *
 * @author Angatu Sistemas
 */
public record BusinessProfile(String jid, String description, String category, String email, String address, List<String> websites) {

    public BusinessProfile {
        websites = websites == null ? List.of() : List.copyOf(websites);
    }
}
