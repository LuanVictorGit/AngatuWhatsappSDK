package com.angatusistemas.whatsapp.model;

import com.fasterxml.jackson.databind.JsonNode;

/** One entry of {@link Group#participants()}. Mirrors Baileys' {@code GroupParticipant} (a {@code Contact} plus admin flags). */
public record GroupParticipant(String id, String name, boolean isAdmin, boolean isSuperAdmin) {

    public static GroupParticipant fromJson(JsonNode data) {
        String name = textOrNull(data, "name");
        if (name == null) {
            name = textOrNull(data, "notify");
        }
        return new GroupParticipant(
                textOrNull(data, "id"),
                name,
                data.path("isAdmin").asBoolean(false),
                data.path("isSuperAdmin").asBoolean(false));
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asText();
    }
}
