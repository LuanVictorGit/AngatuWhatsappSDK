package com.angatusistemas.whatsapp.protocol;

import com.angatusistemas.whatsapp.exception.BridgeException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link ProtocolCodec} in isolation: every {@link ProtocolMessage}
 * variant round-trips through encode/decode unchanged, and every malformed
 * or unrecognized input on decode fails with a {@link BridgeException}
 * carrying {@link BridgeErrorCode#INVALID_MESSAGE} rather than an
 * unchecked/undiagnosable Jackson exception.
 */
class ProtocolCodecTest {

    private final ProtocolCodec codec = new ProtocolCodec();

    @Test
    void roundTripsHandshake() {
        ProtocolMessage.Handshake original = new ProtocolMessage.Handshake("req-1", "1.0", "secret-token");

        assertEquals(original, codec.decode(codec.encode(original)));
    }

    @Test
    void roundTripsHandshakeAck() {
        ProtocolMessage.HandshakeAck original =
                new ProtocolMessage.HandshakeAck("req-1", "1.0", "0.1.0", "6.7.24", 4321L);

        assertEquals(original, codec.decode(codec.encode(original)));
    }

    @Test
    void roundTripsHeartbeatAndAck() {
        assertEquals(new ProtocolMessage.Heartbeat("hb-1"),
                codec.decode(codec.encode(new ProtocolMessage.Heartbeat("hb-1"))));
        assertEquals(new ProtocolMessage.HeartbeatAck("hb-1"),
                codec.decode(codec.encode(new ProtocolMessage.HeartbeatAck("hb-1"))));
    }

    @Test
    void roundTripsRequestWithPayload() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("to", "5511999999999@s.whatsapp.net");
        ProtocolMessage.Request original = new ProtocolMessage.Request("req-2", "send_text", "inst-1", payload);

        assertEquals(original, codec.decode(codec.encode(original)));
    }

    @Test
    void encodingRequestWithNullPayloadSubstitutesEmptyObject() {
        ProtocolMessage.Request original = new ProtocolMessage.Request("req-3", "list_instances", null, null);

        ProtocolMessage.Request decoded = (ProtocolMessage.Request) codec.decode(codec.encode(original));

        assertNull(decoded.instanceId());
        assertNotNull(decoded.payload());
        assertTrue(decoded.payload().isObject());
        assertEquals(0, decoded.payload().size());
    }

    @Test
    void roundTripsSuccessfulResponse() {
        JsonNode data = JsonNodeFactory.instance.objectNode().put("messageId", "abc-123");
        ProtocolMessage.Response original = new ProtocolMessage.Response("req-4", true, data, null);

        assertEquals(original, codec.decode(codec.encode(original)));
    }

    @Test
    void roundTripsFailedResponseWithErrorDetails() {
        ErrorPayload error = new ErrorPayload(
                "INSTANCE_NOT_FOUND", "instancia desconhecida", Map.of("instanceId", "ghost"));
        ProtocolMessage.Response original = new ProtocolMessage.Response("req-5", false, null, error);

        assertEquals(original, codec.decode(codec.encode(original)));
    }

    @Test
    void roundTripsEvent() {
        JsonNode data = JsonNodeFactory.instance.objectNode().put("qr", "raw-qr-content");
        ProtocolMessage.Event original = new ProtocolMessage.Event("evt-1", "qr_code", "inst-1", data);

        assertEquals(original, codec.decode(codec.encode(original)));
    }

    @Test
    void roundTripsProtocolError() {
        ErrorPayload error = new ErrorPayload("INVALID_TOKEN", "token invalido", Map.of());
        ProtocolMessage.ProtocolError original = new ProtocolMessage.ProtocolError("req-6", error);

        assertEquals(original, codec.decode(codec.encode(original)));
    }

    @Test
    void decodeDefaultsMissingIdToEmptyString() {
        ProtocolMessage.Heartbeat decoded = (ProtocolMessage.Heartbeat) codec.decode("{\"type\":\"heartbeat\"}");

        assertEquals("", decoded.id());
    }

    @Test
    void decodeRejectsMalformedJsonSyntax() {
        BridgeException ex = assertThrows(BridgeException.class, () -> codec.decode("{not valid json"));

        assertEquals(BridgeErrorCode.INVALID_MESSAGE, ex.errorCode().orElseThrow());
    }

    @Test
    void decodeRejectsNonObjectJson() {
        BridgeException ex = assertThrows(BridgeException.class, () -> codec.decode("[1,2,3]"));

        assertEquals(BridgeErrorCode.INVALID_MESSAGE, ex.errorCode().orElseThrow());
    }

    @Test
    void decodeRejectsObjectMissingTypeField() {
        BridgeException ex = assertThrows(BridgeException.class, () -> codec.decode("{\"id\":\"x\"}"));

        assertEquals(BridgeErrorCode.INVALID_MESSAGE, ex.errorCode().orElseThrow());
    }

    @Test
    void decodeRejectsUnknownTypeValue() {
        BridgeException ex = assertThrows(BridgeException.class,
                () -> codec.decode("{\"type\":\"not_a_real_type\",\"id\":\"x\"}"));

        assertEquals(BridgeErrorCode.INVALID_MESSAGE, ex.errorCode().orElseThrow());
        assertTrue(ex.getMessage().contains("not_a_real_type"));
    }

    @Test
    void decodeRejectsErrorEnvelopeMissingErrorField() {
        BridgeException ex = assertThrows(BridgeException.class,
                () -> codec.decode("{\"type\":\"error\",\"id\":\"x\"}"));

        assertEquals(BridgeErrorCode.INVALID_MESSAGE, ex.errorCode().orElseThrow());
    }

    @Test
    void toJsonNodeAndFromJsonNodeRoundTripArbitraryValue() {
        Point original = new Point(3, 4);

        JsonNode node = codec.toJsonNode(original);
        Point decoded = codec.fromJsonNode(node, Point.class);

        assertEquals(original, decoded);
    }

    @Test
    void fromJsonNodeRejectsShapeMismatch() {
        JsonNode node = JsonNodeFactory.instance.objectNode().put("x", "not-a-number").put("y", 2);

        BridgeException ex = assertThrows(BridgeException.class, () -> codec.fromJsonNode(node, Point.class));

        assertEquals(BridgeErrorCode.INVALID_MESSAGE, ex.errorCode().orElseThrow());
    }

    private record Point(int x, int y) {
    }
}
