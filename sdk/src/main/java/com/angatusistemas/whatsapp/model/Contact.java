package com.angatusistemas.whatsapp.model;

import com.fasterxml.jackson.databind.JsonNode;

/** Result of {@code WhatsAppClient.getContact(String)}. */
public record Contact(String jid, String name, String status, String profilePictureUrl, boolean onWhatsApp) {

    public static Contact fromJson(JsonNode data) {
        return new Contact(
                textOrNull(data, "jid"),
                textOrNull(data, "name"),
                textOrNull(data, "status"),
                textOrNull(data, "profilePictureUrl"),
                data.path("onWhatsApp").asBoolean(false));
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asText();
    }
}
