package com.angatusistemas.whatsapp.protocol;

import com.angatusistemas.whatsapp.exception.BridgeException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Encodes {@link ProtocolMessage} instances to the wire JSON format and
 * decodes raw text frames received from the bridge back into them.
 *
 * <p>Note: this class intentionally does not use Java 21 pattern-matching
 * switch over the sealed {@link ProtocolMessage} hierarchy (the project
 * targets Java 17, where that is still a preview feature); dispatch below
 * uses plain {@code instanceof} pattern matching instead, which has been
 * stable since Java 16.
 */
public final class ProtocolCodec {

    private final ObjectMapper mapper;

    public ProtocolCodec() {
        this(new ObjectMapper());
    }

    public ProtocolCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String encode(ProtocolMessage message) {
        ObjectNode node = mapper.createObjectNode();

        if (message instanceof ProtocolMessage.Handshake m) {
            node.put("type", "handshake");
            node.put("id", m.id());
            node.put("protocolVersion", m.protocolVersion());
            node.put("token", m.token());
        } else if (message instanceof ProtocolMessage.HandshakeAck m) {
            node.put("type", "handshake_ack");
            node.put("id", m.id());
            node.put("protocolVersion", m.protocolVersion());
            node.put("bridgeVersion", m.bridgeVersion());
            node.put("baileysVersion", m.baileysVersion());
            node.put("pid", m.pid());
        } else if (message instanceof ProtocolMessage.Heartbeat m) {
            node.put("type", "heartbeat");
            node.put("id", m.id());
        } else if (message instanceof ProtocolMessage.HeartbeatAck m) {
            node.put("type", "heartbeat_ack");
            node.put("id", m.id());
        } else if (message instanceof ProtocolMessage.Request m) {
            node.put("type", "request");
            node.put("id", m.id());
            node.put("action", m.action());
            if (m.instanceId() != null) {
                node.put("instanceId", m.instanceId());
            }
            node.set("payload", m.payload() == null ? mapper.createObjectNode() : m.payload());
        } else if (message instanceof ProtocolMessage.Response m) {
            node.put("type", "response");
            node.put("id", m.id());
            node.put("success", m.success());
            if (m.data() != null) {
                node.set("data", m.data());
            }
            if (m.error() != null) {
                node.set("error", errorToNode(m.error()));
            }
        } else if (message instanceof ProtocolMessage.Event m) {
            node.put("type", "event");
            node.put("id", m.id());
            node.put("event", m.event());
            if (m.instanceId() != null) {
                node.put("instanceId", m.instanceId());
            }
            node.set("data", m.data() == null ? mapper.createObjectNode() : m.data());
        } else if (message instanceof ProtocolMessage.ProtocolError m) {
            node.put("type", "error");
            node.put("id", m.id());
            node.set("error", errorToNode(m.error()));
        } else {
            throw new BridgeException("Tipo de ProtocolMessage nao suportado pelo codec: " + message.getClass());
        }

        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new BridgeException("Falha ao serializar mensagem de protocolo", e);
        }
    }

    public ProtocolMessage decode(String json) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new BridgeException(null, BridgeErrorCode.INVALID_MESSAGE,
                    "JSON invalido recebido do bridge: " + e.getOriginalMessage());
        }

        if (root == null || !root.isObject()) {
            throw new BridgeException(null, BridgeErrorCode.INVALID_MESSAGE,
                    "Mensagem de protocolo nao e um objeto JSON");
        }

        String type = textOrNull(root, "type");
        String id = textOrNull(root, "id");
        if (id == null) {
            id = "";
        }
        if (type == null) {
            throw new BridgeException(null, BridgeErrorCode.INVALID_MESSAGE, "Mensagem de protocolo sem campo 'type'");
        }

        return switch (type) {
            case "handshake" -> new ProtocolMessage.Handshake(id, textOrNull(root, "protocolVersion"), textOrNull(root, "token"));
            case "handshake_ack" -> new ProtocolMessage.HandshakeAck(
                    id,
                    textOrNull(root, "protocolVersion"),
                    textOrNull(root, "bridgeVersion"),
                    textOrNull(root, "baileysVersion"),
                    root.path("pid").asLong(0));
            case "heartbeat" -> new ProtocolMessage.Heartbeat(id);
            case "heartbeat_ack" -> new ProtocolMessage.HeartbeatAck(id);
            case "request" -> new ProtocolMessage.Request(id, textOrNull(root, "action"), textOrNull(root, "instanceId"), root.get("payload"));
            case "response" -> new ProtocolMessage.Response(
                    id,
                    root.path("success").asBoolean(false),
                    root.get("data"),
                    nodeToError(root.get("error")));
            case "event" -> new ProtocolMessage.Event(id, textOrNull(root, "event"), textOrNull(root, "instanceId"), root.get("data"));
            case "error" -> {
                ErrorPayload error = nodeToError(root.get("error"));
                if (error == null) {
                    throw new BridgeException(null, BridgeErrorCode.INVALID_MESSAGE, "Envelope 'error' sem campo 'error'");
                }
                yield new ProtocolMessage.ProtocolError(id, error);
            }
            default -> throw new BridgeException(null, BridgeErrorCode.INVALID_MESSAGE, "Tipo de mensagem desconhecido: " + type);
        };
    }

    public JsonNode toJsonNode(Object value) {
        return mapper.valueToTree(value);
    }

    public <T> T fromJsonNode(JsonNode node, Class<T> targetType) {
        try {
            return mapper.treeToValue(node, targetType);
        } catch (JsonProcessingException e) {
            throw new BridgeException(null, BridgeErrorCode.INVALID_MESSAGE,
                    "Payload nao corresponde ao formato esperado (" + targetType.getSimpleName() + "): " + e.getOriginalMessage());
        }
    }

    private ObjectNode errorToNode(ErrorPayload error) {
        ObjectNode node = mapper.createObjectNode();
        node.put("code", error.code());
        node.put("message", error.message());
        if (error.details() != null && !error.details().isEmpty()) {
            node.set("details", mapper.valueToTree(error.details()));
        }
        return node;
    }

    private ErrorPayload nodeToError(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        String code = textOrNull(node, "code");
        String message = textOrNull(node, "message");
        Map<String, Object> details = Map.of();
        JsonNode detailsNode = node.get("details");
        if (detailsNode != null && detailsNode.isObject()) {
            details = mapper.convertValue(detailsNode, new TypeReference<Map<String, Object>>() {
            });
        }
        return new ErrorPayload(code == null ? "UNKNOWN" : code, message == null ? "" : message, details);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asText();
    }
}
