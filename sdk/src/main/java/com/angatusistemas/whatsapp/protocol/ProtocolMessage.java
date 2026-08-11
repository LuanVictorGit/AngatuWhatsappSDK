package com.angatusistemas.whatsapp.protocol;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Wire protocol exchanged with the Node bridge over the local WebSocket.
 * Mirrors {@code bridge/src/protocol/messages.ts} exactly - this sealed
 * hierarchy is the Java side of that shared contract. Payload/data bodies
 * are kept as a raw {@link JsonNode} here; {@code BridgeConnection} and the
 * command layer convert them to concrete DTOs once the action/event name is
 * known, the same way the bridge treats them as a loosely typed
 * {@code Record<string, unknown>} until a specific handler validates them.
 */
public sealed interface ProtocolMessage {

    String id();

    record Handshake(String id, String protocolVersion, String token) implements ProtocolMessage {
    }

    record HandshakeAck(String id, String protocolVersion, String bridgeVersion, String baileysVersion, long pid)
            implements ProtocolMessage {
    }

    record Heartbeat(String id) implements ProtocolMessage {
    }

    record HeartbeatAck(String id) implements ProtocolMessage {
    }

    record Request(String id, String action, String instanceId, JsonNode payload) implements ProtocolMessage {
    }

    record Response(String id, boolean success, JsonNode data, ErrorPayload error) implements ProtocolMessage {
    }

    record Event(String id, String event, String instanceId, JsonNode data) implements ProtocolMessage {
    }

    record ProtocolError(String id, ErrorPayload error) implements ProtocolMessage {
    }
}
