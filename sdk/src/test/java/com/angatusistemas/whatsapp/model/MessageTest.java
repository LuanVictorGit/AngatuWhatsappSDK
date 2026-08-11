package com.angatusistemas.whatsapp.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Message#fromJson} is the sole place bridge wire JSON turns into the
 * public {@link Message} model, so its field mapping, its Baileys-derived
 * defaults, and {@code rawData}'s null-vs-{@link com.fasterxml.jackson.databind.node.NullNode}
 * distinction are worth pinning down directly rather than only indirectly
 * through end-to-end scenarios.
 */
class MessageTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mapsAllFieldsIncludingQuotedMessageAndRawData() throws Exception {
        JsonNode data = mapper.readTree("""
                {
                  "messageId": "3EB0-ABC",
                  "chatId": "5511999999999@s.whatsapp.net",
                  "senderId": "5511999999999@s.whatsapp.net",
                  "timestamp": 1750000000,
                  "text": "ola!",
                  "type": "TEXT",
                  "isGroup": false,
                  "isFromMe": true,
                  "pushName": "Fulano",
                  "quotedMessage": {
                    "messageId": "3EB0-XYZ",
                    "senderId": "5511888888888@s.whatsapp.net",
                    "text": "mensagem original"
                  },
                  "rawData": { "someBaileysField": 42 }
                }
                """);

        Message message = Message.fromJson("inst-1", data);

        assertEquals("3EB0-ABC", message.messageId());
        assertEquals("inst-1", message.instanceId());
        assertEquals("5511999999999@s.whatsapp.net", message.chatId());
        assertEquals("5511999999999@s.whatsapp.net", message.senderId());
        assertEquals(1750000000L, message.timestamp());
        assertEquals("ola!", message.text());
        assertEquals(MessageType.TEXT, message.type());
        assertFalse(message.isGroup());
        assertTrue(message.isFromMe());
        assertEquals("Fulano", message.pushName());
        assertEquals(Instant.ofEpochSecond(1750000000L), message.timestampAsInstant());

        QuotedMessage quoted = message.quotedMessage();
        assertEquals("3EB0-XYZ", quoted.messageId());
        assertEquals("5511888888888@s.whatsapp.net", quoted.senderId());
        assertEquals("mensagem original", quoted.text());

        assertEquals(42, message.rawData().path("someBaileysField").asInt());
    }

    @Test
    void appliesDefaultsWhenOptionalFieldsAreAbsent() throws Exception {
        JsonNode data = mapper.readTree("""
                {
                  "messageId": "3EB0-MIN",
                  "chatId": "5511999999999@s.whatsapp.net",
                  "senderId": "5511999999999@s.whatsapp.net",
                  "timestamp": 1750000001
                }
                """);

        Message message = Message.fromJson("inst-1", data);

        assertNull(message.text());
        assertEquals(MessageType.UNKNOWN, message.type());
        assertFalse(message.isGroup());
        assertFalse(message.isFromMe());
        assertNull(message.pushName());
        assertNull(message.quotedMessage());
        assertTrue(message.rawData().isNull(), "rawData ausente deveria virar NullNode, nao null Java");
    }

    @Test
    void unrecognizedTypeMapsToUnknown() throws Exception {
        JsonNode data = mapper.readTree("""
                {
                  "messageId": "3EB0-FUTURE",
                  "chatId": "5511999999999@s.whatsapp.net",
                  "senderId": "5511999999999@s.whatsapp.net",
                  "timestamp": 1750000002,
                  "type": "SOME_FUTURE_TYPE"
                }
                """);

        Message message = Message.fromJson("inst-1", data);

        assertEquals(MessageType.UNKNOWN, message.type());
    }
}
