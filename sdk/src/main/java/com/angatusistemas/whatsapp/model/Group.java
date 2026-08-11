package com.angatusistemas.whatsapp.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Covers the stable, common fields of Baileys' own {@code GroupMetadata}.
 * That native type has grown a number of narrower/newer fields over time
 * (community flags, join-approval mode, addressing mode, ...) that are not
 * worth an abstraction yet; {@code rawData} carries the full object for
 * anything not exposed here.
 */
public record Group(
        String id,
        String subject,
        String owner,
        String description,
        long creationTimestamp,
        List<GroupParticipant> participants,
        JsonNode rawData) {

    public static Group fromJson(JsonNode data) {
        List<GroupParticipant> participants = new ArrayList<>();
        JsonNode participantsNode = data.get("participants");
        if (participantsNode != null && participantsNode.isArray()) {
            for (JsonNode participant : participantsNode) {
                participants.add(GroupParticipant.fromJson(participant));
            }
        }
        return new Group(
                textOrNull(data, "id"),
                textOrNull(data, "subject"),
                textOrNull(data, "owner"),
                textOrNull(data, "desc"),
                data.path("creation").asLong(0),
                Collections.unmodifiableList(participants),
                data);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asText();
    }
}
