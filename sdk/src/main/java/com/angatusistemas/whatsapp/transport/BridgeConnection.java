package com.angatusistemas.whatsapp.transport;

import com.angatusistemas.whatsapp.exception.BridgeException;
import com.angatusistemas.whatsapp.exception.BridgeExceptionTranslator;
import com.angatusistemas.whatsapp.exception.ConnectionException;
import com.angatusistemas.whatsapp.exception.WhatsAppException;
import com.angatusistemas.whatsapp.protocol.BridgeErrorCode;
import com.angatusistemas.whatsapp.protocol.ErrorPayload;
import com.angatusistemas.whatsapp.protocol.ProtocolCodec;
import com.angatusistemas.whatsapp.protocol.ProtocolMessage;
import com.angatusistemas.whatsapp.protocol.ProtocolVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * One WebSocket connection to a running bridge process: performs the
 * application-level handshake (token + protocol version, distinct from the
 * WebSocket protocol's own opening handshake, which {@link WebSocketClient}
 * already handles), correlates {@code Request}/{@code Response} pairs by id,
 * sends periodic {@code Heartbeat} messages, and dispatches {@code Event}
 * messages to a caller-supplied listener.
 *
 * <p>This class owns exactly one connection attempt - it is not reused
 * across reconnects. Deciding whether/when to reconnect (and distinguishing
 * why the previous connection ended) is the caller's job; see
 * {@link #whenClosed()}.
 *
 * <p>Uses {@code org.java_websocket} rather than the JDK-builtin
 * {@code java.net.http.WebSocket}: constructing the built-in client opens a
 * {@code java.nio.channels.Selector}, whose internal wakeup mechanism needs
 * a loopback socket connection that fails in some restricted/sandboxed
 * Windows environments. Since this class carries every byte of Java-bridge
 * traffic, that risk was not worth taking for a dependency this project
 * already had pinned.
 */
public final class BridgeConnection {

    private static final Logger log = LoggerFactory.getLogger(BridgeConnection.class);

    private static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(20);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final int CLOSE_NORMAL = 1000;
    private static final int CLOSE_HEARTBEAT_TIMEOUT = 4900;

    private final String token;
    private final Duration heartbeatInterval;
    private final ProtocolCodec codec = new ProtocolCodec();
    private final WebSocketClient client;

    private final ExecutorService dispatchExecutor = newSingleThreadExecutor("angatu-bridge-dispatch");
    private final ExecutorService eventExecutor = newSingleThreadExecutor("angatu-bridge-events");
    private final ScheduledExecutorService heartbeatExecutor = newSingleThreadScheduledExecutor("angatu-bridge-heartbeat");

    private final Map<String, CompletableFuture<ProtocolMessage.Response>> pendingRequests = new ConcurrentHashMap<>();
    private final CompletableFuture<ProtocolMessage.HandshakeAck> handshakeFuture = new CompletableFuture<>();
    private final CompletableFuture<DisconnectInfo> closedFuture = new CompletableFuture<>();
    private final AtomicBoolean awaitingHeartbeatAck = new AtomicBoolean(false);
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);

    private volatile Consumer<ProtocolMessage.Event> eventListener;

    public BridgeConnection(URI uri, String token) {
        this(uri, token, DEFAULT_HEARTBEAT_INTERVAL);
    }

    /** Package-private: lets tests use a short heartbeat interval instead of waiting on {@link #DEFAULT_HEARTBEAT_INTERVAL}. */
    BridgeConnection(URI uri, String token, Duration heartbeatInterval) {
        this.token = Objects.requireNonNull(token, "token");
        this.heartbeatInterval = Objects.requireNonNull(heartbeatInterval, "heartbeatInterval");
        this.client = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake handshakedata) {
                handleOpen();
            }

            @Override
            public void onMessage(String message) {
                dispatchExecutor.execute(() -> routeMessage(message));
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                handleClose(code, reason, remote);
            }

            @Override
            public void onError(Exception ex) {
                // onClose is guaranteed to follow (Java-WebSocket invariant), which is
                // where all cleanup/future-completion happens; this is purely diagnostic.
                log.warn("erro na conexao com o bridge: {}", ex.getMessage());
            }
        };
        // We implement our own application-level heartbeat (matches what the bridge's
        // WebSocketServer actually tracks for staleness); disable the library's
        // separate WS-ping-based keepalive so the two mechanisms cannot disagree.
        this.client.setConnectionLostTimeout(0);
    }

    /** Connects and completes once the bridge has accepted our application-level handshake. */
    public CompletableFuture<ProtocolMessage.HandshakeAck> connect(Duration timeout) {
        if (!state.compareAndSet(State.NEW, State.CONNECTING)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("connect() so pode ser chamado uma vez por BridgeConnection"));
        }

        client.connect();
        return handshakeFuture
                .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .handle((ack, ex) -> {
                    if (ex != null) {
                        close(CLOSE_NORMAL, "falha no handshake");
                        throw translateAsyncFailure(ex, null, "conectar ao bridge");
                    }
                    return ack;
                });
    }

    public CompletableFuture<JsonNode> sendRequest(String action, String instanceId, JsonNode payload) {
        return sendRequest(action, instanceId, payload, DEFAULT_REQUEST_TIMEOUT);
    }

    public CompletableFuture<JsonNode> sendRequest(String action, String instanceId, JsonNode payload, Duration timeout) {
        if (state.get() != State.OPEN) {
            return CompletableFuture.failedFuture(new BridgeException(
                    "nao e possivel enviar a acao '" + action + "': conexao com o bridge nao esta aberta (estado=" + state.get() + ")"));
        }

        String id = UUID.randomUUID().toString();
        CompletableFuture<ProtocolMessage.Response> responseFuture = new CompletableFuture<>();
        pendingRequests.put(id, responseFuture);

        try {
            client.send(codec.encode(new ProtocolMessage.Request(id, action, instanceId, payload)));
        } catch (RuntimeException e) {
            pendingRequests.remove(id);
            return CompletableFuture.failedFuture(new BridgeException("falha ao enviar a acao '" + action + "' ao bridge", e));
        }

        return responseFuture
                .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .handle((response, ex) -> {
                    pendingRequests.remove(id);
                    if (ex != null) {
                        throw translateAsyncFailure(ex, instanceId, "executar a acao '" + action + "'");
                    }
                    if (!response.success()) {
                        ErrorPayload error = response.error() != null
                                ? response.error()
                                : new ErrorPayload(BridgeErrorCode.INTERNAL_ERROR.name(), "resposta de falha sem detalhes de erro", Map.of());
                        throw BridgeExceptionTranslator.translate(instanceId, error);
                    }
                    return response.data() != null ? response.data() : JsonNodeFactory.instance.objectNode();
                });
    }

    public void setEventListener(Consumer<ProtocolMessage.Event> listener) {
        this.eventListener = listener;
    }

    /** Completes exactly once, when this connection closes for any reason (clean or not). */
    public CompletableFuture<DisconnectInfo> whenClosed() {
        return closedFuture;
    }

    public boolean isOpen() {
        return state.get() == State.OPEN;
    }

    public void close() {
        close(CLOSE_NORMAL, "encerrado pelo cliente");
    }

    public void close(int code, String reason) {
        if (state.getAndSet(State.CLOSED) == State.CLOSED) {
            return;
        }
        client.close(code, reason);
    }

    private void handleOpen() {
        String id = UUID.randomUUID().toString();
        client.send(codec.encode(new ProtocolMessage.Handshake(id, ProtocolVersion.CURRENT, token)));
    }

    private void routeMessage(String raw) {
        ProtocolMessage message;
        try {
            message = codec.decode(raw);
        } catch (WhatsAppException e) {
            log.warn("mensagem invalida recebida do bridge, ignorando: {}", e.getMessage());
            return;
        }

        if (message instanceof ProtocolMessage.HandshakeAck ack) {
            handleHandshakeAck(ack);
        } else if (message instanceof ProtocolMessage.HeartbeatAck) {
            awaitingHeartbeatAck.set(false);
        } else if (message instanceof ProtocolMessage.Response response) {
            completeRequest(response);
        } else if (message instanceof ProtocolMessage.Event event) {
            dispatchEvent(event);
        } else if (message instanceof ProtocolMessage.ProtocolError error) {
            handleProtocolError(error);
        } else {
            log.warn("tipo de mensagem inesperado recebido do bridge: {}", message.getClass().getSimpleName());
        }
    }

    private void handleHandshakeAck(ProtocolMessage.HandshakeAck ack) {
        if (!ProtocolVersion.isCompatible(ack.protocolVersion())) {
            handshakeFuture.completeExceptionally(new BridgeException(null, BridgeErrorCode.PROTOCOL_VERSION_MISMATCH,
                    "versao de protocolo do bridge incompativel: bridge=" + ack.protocolVersion() + " sdk=" + ProtocolVersion.CURRENT));
            close(4002, "protocol version mismatch");
            return;
        }
        state.set(State.OPEN);
        heartbeatExecutor.scheduleWithFixedDelay(this::heartbeatTick,
                heartbeatInterval.toMillis(), heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);
        log.info("conectado ao bridge (bridgeVersion={}, baileysVersion={}, pid={})",
                ack.bridgeVersion(), ack.baileysVersion(), ack.pid());
        handshakeFuture.complete(ack);
    }

    private void completeRequest(ProtocolMessage.Response response) {
        CompletableFuture<ProtocolMessage.Response> future = pendingRequests.remove(response.id());
        if (future == null) {
            log.debug("resposta recebida para uma requisicao desconhecida ou que ja expirou (id={})", response.id());
            return;
        }
        future.complete(response);
    }

    private void dispatchEvent(ProtocolMessage.Event event) {
        Consumer<ProtocolMessage.Event> listener = this.eventListener;
        if (listener == null) {
            return;
        }
        eventExecutor.execute(() -> {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                log.warn("listener de eventos lancou uma excecao nao tratada para o evento '{}': {}", event.event(), e.getMessage(), e);
            }
        });
    }

    private void handleProtocolError(ProtocolMessage.ProtocolError error) {
        if (!handshakeFuture.isDone()) {
            handshakeFuture.completeExceptionally(BridgeExceptionTranslator.translate(null, error.error()));
            return;
        }
        CompletableFuture<ProtocolMessage.Response> pending = pendingRequests.remove(error.id());
        if (pending != null) {
            pending.completeExceptionally(BridgeExceptionTranslator.translate(null, error.error()));
            return;
        }
        log.warn("bridge enviou um erro de protocolo nao correlacionado a nenhuma requisicao pendente: code={} message={}",
                error.error().code(), error.error().message());
    }

    private void heartbeatTick() {
        if (state.get() != State.OPEN) {
            return;
        }
        if (!awaitingHeartbeatAck.compareAndSet(false, true)) {
            log.warn("nenhum heartbeat_ack recebido do bridge desde o ultimo ciclo - considerando a conexao perdida");
            state.set(State.CLOSED);
            client.closeConnection(CLOSE_HEARTBEAT_TIMEOUT, "heartbeat timeout");
            return;
        }
        try {
            client.send(codec.encode(new ProtocolMessage.Heartbeat(UUID.randomUUID().toString())));
        } catch (RuntimeException e) {
            log.debug("falha ao enviar heartbeat (conexao provavelmente ja fechada): {}", e.getMessage());
        }
    }

    private void handleClose(int code, String reason, boolean remote) {
        state.set(State.CLOSED);

        WhatsAppException closeError = new ConnectionException(null, BridgeErrorCode.NOT_CONNECTED,
                "conexao com o bridge fechada (code=" + code + ", reason=" + reason + ", remote=" + remote + ")");
        if (!handshakeFuture.isDone()) {
            handshakeFuture.completeExceptionally(closeError);
        }
        for (String id : new ArrayList<>(pendingRequests.keySet())) {
            CompletableFuture<ProtocolMessage.Response> pending = pendingRequests.remove(id);
            if (pending != null) {
                pending.completeExceptionally(closeError);
            }
        }

        closedFuture.complete(new DisconnectInfo(code, reason, remote));
        dispatchExecutor.shutdown();
        eventExecutor.shutdown();
        heartbeatExecutor.shutdown();
    }

    private RuntimeException translateAsyncFailure(Throwable ex, String instanceId, String action) {
        if (ex instanceof java.util.concurrent.TimeoutException) {
            return new com.angatusistemas.whatsapp.exception.TimeoutException(
                    instanceId, BridgeErrorCode.TIMEOUT, "timeout ao " + action);
        }
        if (ex instanceof RuntimeException re) {
            return re;
        }
        return new BridgeException("falha ao " + action, ex);
    }

    private static ExecutorService newSingleThreadExecutor(String name) {
        return Executors.newSingleThreadExecutor(runnable -> daemonThread(runnable, name));
    }

    private static ScheduledExecutorService newSingleThreadScheduledExecutor(String name) {
        return Executors.newSingleThreadScheduledExecutor(runnable -> daemonThread(runnable, name));
    }

    private static Thread daemonThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private enum State {
        NEW,
        CONNECTING,
        OPEN,
        CLOSED
    }
}
