package com.angatusistemas.whatsapp.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.time.Instant;

/**
 * Abstract, Baileys-independent view of a WhatsApp message. Mirrors
 * {@code AbstractMessage} in {@code bridge/src/adapter/MessageMapper.ts}.
 *
 * <p>{@code rawData} carries the underlying Baileys {@code WAMessage} object
 * exactly as received (as a {@link JsonNode}), for the rare case something
 * genuinely needs a field this abstraction does not expose - the rest of
 * this API is deliberately not coupled to Baileys' own message shape, since
 * that shape is not part of any stability contract Baileys makes.
 */
public record Message(
        String messageId,
        String instanceId,
        String chatId,
        String senderId,
        long timestamp,
        String text,
        MessageType type,
        boolean isGroup,
        boolean isFromMe,
        String pushName,
        QuotedMessage quotedMessage,
        JsonNode rawData) {

    public Instant timestampAsInstant() {
        return Instant.ofEpochSecond(timestamp);
    }

    public static Message fromJson(String instanceId, JsonNode data) {
        JsonNode quotedNode = data.get("quotedMessage");
        QuotedMessage quoted = (quotedNode == null || quotedNode.isNull())
                ? null
                : new QuotedMessage(
                        text(quotedNode, "messageId"),
                        text(quotedNode, "senderId"),
                        text(quotedNode, "text"));

        JsonNode raw = data.get("rawData");
        return new Message(
                text(data, "messageId"),
                instanceId,
                text(data, "chatId"),
                text(data, "senderId"),
                data.path("timestamp").asLong(0),
                text(data, "text"),
                MessageType.fromWireValue(text(data, "type")),
                data.path("isGroup").asBoolean(false),
                data.path("isFromMe").asBoolean(false),
                text(data, "pushName"),
                quoted,
                raw != null ? raw : NullNode.getInstance());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asText();
    }
}
