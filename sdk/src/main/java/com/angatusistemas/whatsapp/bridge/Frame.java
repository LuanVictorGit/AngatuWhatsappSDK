package com.angatusistemas.whatsapp.bridge;

import com.angatusistemas.whatsapp.exception.BridgeErrorCode;
import com.angatusistemas.whatsapp.internal.Json;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * One frame of the local WebSocket protocol between the SDK and the bridge, decoded from and
 * encoded to its JSON text. Protocol 2.x: a frame the bridge sends is a handshake ack, a heartbeat
 * ack, a response to a request, an event, or a protocol error.
 *
 * @author Angatu Sistemas
 */
public sealed interface Frame {

    String PROTOCOL_VERSION = "2.0";

    static Frame decode(String text) {
        JsonObject json;
        try {
            json = JsonParser.parseString(text).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            return new Unknown("not a JSON object");
        }
        String type = Json.str(json, "type");
        if (type == null) {
            return new Unknown("frame without a type");
        }
        return switch (type) {
            case "handshake_ack" -> new HandshakeAck(Json.str(json, "protocolVersion"), Json.str(json, "bridgeVersion"),
                    Json.str(json, "baileysVersion"), Json.number(json, "pid", -1));
            case "heartbeat_ack" -> new HeartbeatAck();
            case "response" -> response(json);
            case "event" -> new Event(Json.str(json, "event"), Json.str(json, "instanceId"),
                    json.has("data") && json.get("data").isJsonObject() ? json.getAsJsonObject("data") : new JsonObject());
            case "error" -> {
                JsonObject error = Json.object(json, "error");
                yield new ProtocolError(Json.str(json, "id"), BridgeErrorCode.fromWire(Json.str(error, "code")), Json.str(error, "message"));
            }
            default -> new Unknown("unknown frame type " + type);
        };
    }

    static String handshake(String id, String token) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "handshake");
        frame.addProperty("id", id);
        frame.addProperty("protocolVersion", PROTOCOL_VERSION);
        frame.addProperty("token", token);
        return frame.toString();
    }

    static String heartbeat(String id) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "heartbeat");
        frame.addProperty("id", id);
        return frame.toString();
    }

    static String request(String id, String action, String instanceId, JsonObject payload) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "request");
        frame.addProperty("id", id);
        frame.addProperty("action", action);
        Json.put(frame, "instanceId", instanceId);
        frame.add("payload", payload == null ? new JsonObject() : payload);
        return frame.toString();
    }

    static boolean isCompatible(String remoteVersion) {
        return remoteVersion != null && remoteVersion.split("\\.")[0].equals(PROTOCOL_VERSION.split("\\.")[0]);
    }

    private static Response response(JsonObject json) {
        JsonObject error = Json.object(json, "error");
        return new Response(Json.str(json, "id"), Json.bool(json, "success", false),
                json.has("data") && json.get("data").isJsonObject() ? json.getAsJsonObject("data") : new JsonObject(),
                error == null ? null : BridgeErrorCode.fromWire(Json.str(error, "code")),
                error == null ? null : Json.str(error, "message"));
    }

    record HandshakeAck(String protocolVersion, String bridgeVersion, String baileysVersion, long pid) implements Frame {
    }

    record HeartbeatAck() implements Frame {
    }

    record Response(String id, boolean success, JsonObject data, BridgeErrorCode errorCode, String errorMessage) implements Frame {
    }

    record Event(String event, String instanceId, JsonObject data) implements Frame {
    }

    record ProtocolError(String id, BridgeErrorCode code, String message) implements Frame {
    }

    record Unknown(String reason) implements Frame {
    }
}
