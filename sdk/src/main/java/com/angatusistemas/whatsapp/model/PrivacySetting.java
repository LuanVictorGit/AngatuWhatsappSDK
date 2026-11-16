package com.angatusistemas.whatsapp.model;

import java.util.Locale;
import java.util.Set;

/**
 * Account privacy settings and the values WhatsApp accepts for each.
 *
 * @author Angatu Sistemas
 */
public enum PrivacySetting {
    LAST_SEEN(Set.of("all", "contacts", "contact_blacklist", "none")),
    ONLINE(Set.of("all", "match_last_seen")),
    PROFILE_PICTURE(Set.of("all", "contacts", "contact_blacklist", "none")),
    STATUS(Set.of("all", "contacts", "contact_blacklist", "none")),
    READ_RECEIPTS(Set.of("all", "none")),
    GROUPS_ADD(Set.of("all", "contacts", "contact_blacklist"));

    private final Set<String> accepted;

    PrivacySetting(Set<String> accepted) {
        this.accepted = accepted;
    }

    public Set<String> accepted() {
        return accepted;
    }

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
