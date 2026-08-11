package com.angatusistemas.whatsapp.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Group#fromJson} deliberately reads Baileys' own
 * {@code GroupMetadata} field names ({@code desc}, {@code creation}) rather
 * than this SDK's public accessor names ({@code description},
 * {@code creationTimestamp}) - see the class Javadoc on {@link Group}. That
 * name mismatch is exactly the kind of thing worth pinning down in a test
 * rather than leaving to be silently broken by a future refactor.
 */
class GroupTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mapsBaileysNativeFieldNamesAndParticipants() throws Exception {
        JsonNode data = mapper.readTree("""
                {
                  "id": "123456-group@g.us",
                  "subject": "Grupo de Teste",
                  "owner": "5511999999999@s.whatsapp.net",
                  "desc": "descricao do grupo",
                  "creation": 1750000000,
                  "participants": [
                    { "id": "5511999999999@s.whatsapp.net", "name": "Admin", "isAdmin": true, "isSuperAdmin": true },
                    { "id": "5511888888888@s.whatsapp.net", "notify": "Membro Via Notify", "isAdmin": false, "isSuperAdmin": false }
                  ]
                }
                """);

        Group group = Group.fromJson(data);

        assertEquals("123456-group@g.us", group.id());
        assertEquals("Grupo de Teste", group.subject());
        assertEquals("5511999999999@s.whatsapp.net", group.owner());
        assertEquals("descricao do grupo", group.description());
        assertEquals(1750000000L, group.creationTimestamp());
        assertEquals(2, group.participants().size());

        GroupParticipant admin = group.participants().get(0);
        assertEquals("5511999999999@s.whatsapp.net", admin.id());
        assertEquals("Admin", admin.name());
        assertTrue(admin.isAdmin());
        assertTrue(admin.isSuperAdmin());

        GroupParticipant memberWithoutName = group.participants().get(1);
        assertEquals("Membro Via Notify", memberWithoutName.name());
        assertFalse(memberWithoutName.isAdmin());
        assertFalse(memberWithoutName.isSuperAdmin());

        assertTrue(group.rawData().has("participants"), "rawData deveria preservar o JSON original completo");
    }

    @Test
    void appliesDefaultsWhenOptionalFieldsAreAbsent() throws Exception {
        JsonNode data = mapper.readTree("{\"id\": \"123456-group@g.us\"}");

        Group group = Group.fromJson(data);

        assertEquals("123456-group@g.us", group.id());
        assertNull(group.subject());
        assertNull(group.owner());
        assertNull(group.description());
        assertEquals(0L, group.creationTimestamp());
        assertTrue(group.participants().isEmpty());
    }

    @Test
    void participantsListIsUnmodifiable() throws Exception {
        JsonNode data = mapper.readTree("{\"id\": \"123456-group@g.us\", \"participants\": []}");

        Group group = Group.fromJson(data);

        assertThrows(UnsupportedOperationException.class,
                () -> group.participants().add(new GroupParticipant("x", "y", false, false)));
    }
}
