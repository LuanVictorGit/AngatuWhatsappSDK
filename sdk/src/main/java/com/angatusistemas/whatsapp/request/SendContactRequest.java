package com.angatusistemas.whatsapp.request;

import java.util.Objects;

/**
 * A contact card (vCard) with a name, a phone number and an optional organization.
 *
 * @author Angatu Sistemas
 */
public record SendContactRequest(String to, String displayName, String phoneNumber, String organization, String quotedMessageId) {

    public SendContactRequest {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(phoneNumber, "phoneNumber");
    }

    public static SendContactRequest of(String to, String displayName, String phoneNumber) {
        return new SendContactRequest(to, displayName, phoneNumber, null, null);
    }

    public SendContactRequest withOrganization(String name) {
        return new SendContactRequest(to, displayName, phoneNumber, name, quotedMessageId);
    }

    public SendContactRequest quoting(String messageId) {
        return new SendContactRequest(to, displayName, phoneNumber, organization, messageId);
    }
}
