package com.angatusistemas.whatsapp.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContactTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mapsAllFields() throws Exception {
        JsonNode data = mapper.readTree("""
                {
                  "jid": "5511999999999@s.whatsapp.net",
                  "name": "Fulano de Tal",
                  "status": "Disponivel",
                  "profilePictureUrl": "https://example.com/pic.jpg",
                  "onWhatsApp": true
                }
                """);

        Contact contact = Contact.fromJson(data);

        assertEquals("5511999999999@s.whatsapp.net", contact.jid());
        assertEquals("Fulano de Tal", contact.name());
        assertEquals("Disponivel", contact.status());
        assertEquals("https://example.com/pic.jpg", contact.profilePictureUrl());
        assertTrue(contact.onWhatsApp());
    }

    @Test
    void appliesDefaultsWhenOptionalFieldsAreAbsent() throws Exception {
        JsonNode data = mapper.readTree("{\"jid\": \"5511999999999@s.whatsapp.net\"}");

        Contact contact = Contact.fromJson(data);

        assertEquals("5511999999999@s.whatsapp.net", contact.jid());
        assertNull(contact.name());
        assertNull(contact.status());
        assertNull(contact.profilePictureUrl());
        assertFalse(contact.onWhatsApp());
    }
}
