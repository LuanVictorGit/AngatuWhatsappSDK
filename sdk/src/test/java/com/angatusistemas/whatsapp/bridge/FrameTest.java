package com.angatusistemas.whatsapp.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.angatusistemas.whatsapp.exception.BridgeErrorCode;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * Encoding and decoding of the bridge protocol frames.
 *
 * @author Angatu Sistemas
 */
class FrameTest {

    @Test
    void decodesEveryFrameType() {
        assertInstanceOf(Frame.HandshakeAck.class, Frame.decode("{\"type\":\"handshake_ack\",\"protocolVersion\":\"2.0\",\"pid\":7}"));
        assertInstanceOf(Frame.HeartbeatAck.class, Frame.decode("{\"type\":\"heartbeat_ack\",\"id\":\"1\"}"));
        Frame.Response failed = (Frame.Response) Frame.decode(
                "{\"type\":\"response\",\"id\":\"9\",\"success\":false,\"error\":{\"code\":\"RATE_LIMITED\",\"message\":\"slow down\"}}");
        assertEquals(BridgeErrorCode.RATE_LIMITED, failed.errorCode());
        Frame.Event event = (Frame.Event) Frame.decode("{\"type\":\"event\",\"event\":\"qr_code\",\"instanceId\":\"a\",\"data\":{\"qr\":\"x\"}}");
        assertEquals("a", event.instanceId());
        assertEquals("x", event.data().get("qr").getAsString());
        assertInstanceOf(Frame.Unknown.class, Frame.decode("not json"));
    }

    @Test
    void encodesRequestsWithTheirPayload() {
        JsonObject payload = new JsonObject();
        payload.addProperty("to", "5511999990000@s.whatsapp.net");
        JsonObject frame = JsonParser.parseString(Frame.request("3", "send_text", "inst", payload)).getAsJsonObject();
        assertEquals("request", frame.get("type").getAsString());
        assertEquals("inst", frame.get("instanceId").getAsString());
        assertEquals("5511999990000@s.whatsapp.net", frame.getAsJsonObject("payload").get("to").getAsString());
    }

    @Test
    void onlyTheMajorVersionMustMatch() {
        assertTrue(Frame.isCompatible("2.7"));
        assertFalse(Frame.isCompatible("1.0"));
        assertFalse(Frame.isCompatible(null));
    }
}
