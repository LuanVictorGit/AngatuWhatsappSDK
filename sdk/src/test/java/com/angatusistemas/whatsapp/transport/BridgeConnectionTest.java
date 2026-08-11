package com.angatusistemas.whatsapp.transport;

import com.angatusistemas.whatsapp.exception.BridgeException;
import com.angatusistemas.whatsapp.exception.ConnectionException;
import com.angatusistemas.whatsapp.exception.InstanceException;
import com.angatusistemas.whatsapp.protocol.ProtocolMessage;
import com.angatusistemas.whatsapp.protocol.ProtocolVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link BridgeConnection} against {@link MockBridgeServer} - a
 * hand-rolled test double standing in for the real Node bridge, so these
 * scenarios run fast and deterministically without spawning Node or
 * depending on network/WhatsApp access. The real bridge process is covered
 * separately by the manually-run end-to-end verification in this project's
 * development history (see README "Testes").
 */
class BridgeConnectionTest {

    private static final String TOKEN = "test-token-1234567890";

    private MockBridgeServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBridgeServer.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
    }

    private BridgeConnection newConnection() {
        return newConnection(Duration.ofSeconds(20));
    }

    private BridgeConnection newConnection(Duration heartbeatInterval) {
        return new BridgeConnection(URI.create("ws://127.0.0.1:" + server.port()), TOKEN, heartbeatInterval);
    }

    private void handshakeAndAck() throws Exception {
        JsonNode handshake = server.nextMessage(Duration.ofSeconds(5));
        assertNotNull(handshake, "esperava receber a mensagem de handshake");
        assertEquals("handshake", handshake.path("type").asText());
        assertEquals(TOKEN, handshake.path("token").asText());

        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("type", "handshake_ack");
        ack.put("id", handshake.path("id").asText());
        ack.put("protocolVersion", ProtocolVersion.CURRENT);
        ack.put("bridgeVersion", "test");
        ack.put("baileysVersion", "test");
        ack.put("pid", 1234);
        server.sendJson(ack);
    }

    @Test
    @Timeout(10)
    void connectSucceedsAfterValidHandshake() throws Exception {
        BridgeConnection connection = newConnection();
        try {
            CompletableFuture<ProtocolMessage.HandshakeAck> future = connection.connect(Duration.ofSeconds(5));
            handshakeAndAck();

            ProtocolMessage.HandshakeAck ack = future.get(5, TimeUnit.SECONDS);
            assertEquals("test", ack.bridgeVersion());
            assertTrue(connection.isOpen());
        } finally {
            connection.close();
        }
    }

    @Test
    @Timeout(10)
    void connectFailsOnProtocolVersionMismatch() throws Exception {
        BridgeConnection connection = newConnection();
        try {
            CompletableFuture<ProtocolMessage.HandshakeAck> future = connection.connect(Duration.ofSeconds(5));
            JsonNode handshake = server.nextMessage(Duration.ofSeconds(5));

            Map<String, Object> ack = new LinkedHashMap<>();
            ack.put("type", "handshake_ack");
            ack.put("id", handshake.path("id").asText());
            ack.put("protocolVersion", "99.0");
            ack.put("bridgeVersion", "test");
            ack.put("baileysVersion", "test");
            ack.put("pid", 1);
            server.sendJson(ack);

            ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
            assertInstanceOf(BridgeException.class, ex.getCause());
        } finally {
            connection.close();
        }
    }

    @Test
    @Timeout(10)
    void connectFailsWhenServerRejectsHandshake() throws Exception {
        BridgeConnection connection = newConnection();
        try {
            CompletableFuture<ProtocolMessage.HandshakeAck> future = connection.connect(Duration.ofSeconds(5));
            JsonNode handshake = server.nextMessage(Duration.ofSeconds(5));

            Map<String, Object> error = new LinkedHashMap<>();
            error.put("type", "error");
            error.put("id", handshake.path("id").asText());
            error.put("error", Map.of("code", "INVALID_TOKEN", "message", "token invalido"));
            server.sendJson(error);

            ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
            assertInstanceOf(BridgeException.class, ex.getCause());
        } finally {
            connection.close();
        }
    }

    @Test
    @Timeout(10)
    void connectTimesOutWhenServerNeverResponds() throws Exception {
        BridgeConnection connection = newConnection();
        try {
            CompletableFuture<ProtocolMessage.HandshakeAck> future = connection.connect(Duration.ofMillis(500));
            server.nextMessage(Duration.ofSeconds(5)); // consume the handshake, but never reply

            ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
            assertInstanceOf(com.angatusistemas.whatsapp.exception.TimeoutException.class, ex.getCause());
        } finally {
            connection.close();
        }
    }

    @Test
    @Timeout(10)
    void requestResponseRoundTrip() throws Exception {
        BridgeConnection connection = newConnection();
        try {
            CompletableFuture<ProtocolMessage.HandshakeAck> connectFuture = connection.connect(Duration.ofSeconds(5));
            handshakeAndAck();
            connectFuture.get(5, TimeUnit.SECONDS);

            CompletableFuture<JsonNode> requestFuture =
                    connection.sendRequest("list_instances", null, JsonNodeFactory.instance.objectNode());
            JsonNode request = server.nextMessage(Duration.ofSeconds(5));
            assertEquals("request", request.path("type").asText());
            assertEquals("list_instances", request.path("action").asText());

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("type", "response");
            response.put("id", request.path("id").asText());
            response.put("success", true);
            response.put("data", Map.of("instanceIds", List.of("a", "b")));
            server.sendJson(response);

            JsonNode data = requestFuture.get(5, TimeUnit.SECONDS);
            assertTrue(data.path("instanceIds").isArray());
            assertEquals(2, data.path("instanceIds").size());
        } finally {
            connection.close();
        }
    }

    @Test
    @Timeout(10)
    void requestFailureTranslatesToTypedException() throws Exception {
        BridgeConnection connection = newConnection();
        try {
            CompletableFuture<ProtocolMessage.HandshakeAck> connectFuture = connection.connect(Duration.ofSeconds(5));
            handshakeAndAck();
            connectFuture.get(5, TimeUnit.SECONDS);

            CompletableFuture<JsonNode> requestFuture =
                    connection.sendRequest("get_status", "ghost", JsonNodeFactory.instance.objectNode());
            JsonNode request = server.nextMessage(Duration.ofSeconds(5));

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("type", "response");
            response.put("id", request.path("id").asText());
            response.put("success", false);
            response.put("error", Map.of("code", "INSTANCE_NOT_FOUND", "message", "nao encontrada"));
            server.sendJson(response);

            ExecutionException ex = assertThrows(ExecutionException.class, () -> requestFuture.get(5, TimeUnit.SECONDS));
            assertInstanceOf(InstanceException.class, ex.getCause());
        } finally {
            connection.close();
        }
    }

    @Test
    @Timeout(10)
    void requestTimesOutWhenNoResponseArrives() throws Exception {
        BridgeConnection connection = newConnection();
        try {
            CompletableFuture<ProtocolMessage.HandshakeAck> connectFuture = connection.connect(Duration.ofSeconds(5));
            handshakeAndAck();
            connectFuture.get(5, TimeUnit.SECONDS);

            CompletableFuture<JsonNode> requestFuture = connection.sendRequest(
                    "list_instances", null, JsonNodeFactory.instance.objectNode(), Duration.ofMillis(300));
            server.nextMessage(Duration.ofSeconds(5)); // consume, but never respond

            ExecutionException ex = assertThrows(ExecutionException.class, () -> requestFuture.get(5, TimeUnit.SECONDS));
            assertInstanceOf(com.angatusistemas.whatsapp.exception.TimeoutException.class, ex.getCause());
        } finally {
            connection.close();
        }
    }

    @Test
    @Timeout(10)
    void eventsAreDispatchedToListener() throws Exception {
        BridgeConnection connection = newConnection();
        try {
            AtomicReference<ProtocolMessage.Event> received = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            connection.setEventListener(event -> {
                received.set(event);
                latch.countDown();
            });

            CompletableFuture<ProtocolMessage.HandshakeAck> connectFuture = connection.connect(Duration.ofSeconds(5));
            handshakeAndAck();
            connectFuture.get(5, TimeUnit.SECONDS);

            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "event");
            event.put("id", "evt-1");
            event.put("event", "qr_code");
            event.put("instanceId", "inst-1");
            event.put("data", Map.of("qr", "raw-qr-content"));
            server.sendJson(event);

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertEquals("qr_code", received.get().event());
            assertEquals("raw-qr-content", received.get().data().path("qr").asText());
        } finally {
            connection.close();
        }
    }

    @Test
    @Timeout(10)
    void heartbeatKeepsConnectionAliveWhenAcked() throws Exception {
        BridgeConnection connection = newConnection(Duration.ofMillis(150));
        try {
            CompletableFuture<ProtocolMessage.HandshakeAck> connectFuture = connection.connect(Duration.ofSeconds(5));
            handshakeAndAck();
            connectFuture.get(5, TimeUnit.SECONDS);

            for (int i = 0; i < 3; i++) {
                JsonNode heartbeat = server.nextMessage(Duration.ofSeconds(2));
                assertNotNull(heartbeat, "esperava um heartbeat");
                assertEquals("heartbeat", heartbeat.path("type").asText());
                server.sendJson(Map.of("type", "heartbeat_ack", "id", heartbeat.path("id").asText()));
            }
            assertTrue(connection.isOpen());
        } finally {
            connection.close();
        }
    }

    @Test
    @Timeout(10)
    void heartbeatTimeoutClosesConnection() throws Exception {
        BridgeConnection connection = newConnection(Duration.ofMillis(150));
        try {
            CompletableFuture<ProtocolMessage.HandshakeAck> connectFuture = connection.connect(Duration.ofSeconds(5));
            handshakeAndAck();
            connectFuture.get(5, TimeUnit.SECONDS);

            server.nextMessage(Duration.ofSeconds(2)); // first heartbeat: deliberately never acked

            DisconnectInfo disconnectInfo = connection.whenClosed().get(5, TimeUnit.SECONDS);
            assertEquals(4900, disconnectInfo.code());
            assertFalse(connection.isOpen());
        } finally {
            connection.close();
        }
    }

    @Test
    @Timeout(10)
    void serverInitiatedCloseIsReportedAsRemote() throws Exception {
        BridgeConnection connection = newConnection();
        try {
            CompletableFuture<ProtocolMessage.HandshakeAck> connectFuture = connection.connect(Duration.ofSeconds(5));
            handshakeAndAck();
            connectFuture.get(5, TimeUnit.SECONDS);

            server.closeConnection(1000, "bridge shutting down");

            DisconnectInfo disconnectInfo = connection.whenClosed().get(5, TimeUnit.SECONDS);
            assertEquals(1000, disconnectInfo.code());
            assertTrue(disconnectInfo.remote());
        } finally {
            connection.close();
        }
    }

    @Test
    @Timeout(10)
    void clientInitiatedCloseIsReportedAsLocal() throws Exception {
        BridgeConnection connection = newConnection();
        CompletableFuture<ProtocolMessage.HandshakeAck> connectFuture = connection.connect(Duration.ofSeconds(5));
        handshakeAndAck();
        connectFuture.get(5, TimeUnit.SECONDS);

        connection.close();

        DisconnectInfo disconnectInfo = connection.whenClosed().get(5, TimeUnit.SECONDS);
        assertFalse(disconnectInfo.remote());
        assertFalse(connection.isOpen());
    }

    @Test
    @Timeout(10)
    void pendingRequestsFailWhenConnectionDropsAbruptly() throws Exception {
        BridgeConnection connection = newConnection();
        try {
            CompletableFuture<ProtocolMessage.HandshakeAck> connectFuture = connection.connect(Duration.ofSeconds(5));
            handshakeAndAck();
            connectFuture.get(5, TimeUnit.SECONDS);

            CompletableFuture<JsonNode> requestFuture = connection.sendRequest(
                    "list_instances", null, JsonNodeFactory.instance.objectNode(), Duration.ofSeconds(10));
            server.nextMessage(Duration.ofSeconds(5));
            server.dropConnectionAbruptly();

            ExecutionException ex = assertThrows(ExecutionException.class, () -> requestFuture.get(5, TimeUnit.SECONDS));
            assertInstanceOf(ConnectionException.class, ex.getCause());
        } finally {
            connection.close();
        }
    }
}
