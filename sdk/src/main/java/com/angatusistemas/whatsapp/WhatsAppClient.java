package com.angatusistemas.whatsapp;

import com.angatusistemas.whatsapp.exception.BridgeException;
import com.angatusistemas.whatsapp.exception.ConnectionException;
import com.angatusistemas.whatsapp.exception.WhatsAppException;
import com.angatusistemas.whatsapp.internal.InstanceRegistry;
import com.angatusistemas.whatsapp.internal.QrCodeRenderer;
import com.angatusistemas.whatsapp.listener.ConnectionStateListener;
import com.angatusistemas.whatsapp.listener.ErrorListener;
import com.angatusistemas.whatsapp.listener.GroupUpdateListener;
import com.angatusistemas.whatsapp.listener.MessageDeletedListener;
import com.angatusistemas.whatsapp.listener.MessageListener;
import com.angatusistemas.whatsapp.listener.MessageUpdatedListener;
import com.angatusistemas.whatsapp.listener.PresenceListener;
import com.angatusistemas.whatsapp.listener.QrCodeListener;
import com.angatusistemas.whatsapp.listener.ReactionListener;
import com.angatusistemas.whatsapp.model.Contact;
import com.angatusistemas.whatsapp.model.Group;
import com.angatusistemas.whatsapp.model.MediaContent;
import com.angatusistemas.whatsapp.model.Message;
import com.angatusistemas.whatsapp.model.NumberCheck;
import com.angatusistemas.whatsapp.pacing.HumanizationOptions;
import com.angatusistemas.whatsapp.pacing.PacingOptions;
import com.angatusistemas.whatsapp.pacing.QueuedSend;
import com.angatusistemas.whatsapp.pacing.ReadReceiptMode;
import com.angatusistemas.whatsapp.pacing.SendPacer;
import com.angatusistemas.whatsapp.protocol.BridgeErrorCode;
import com.angatusistemas.whatsapp.request.DeleteMessageRequest;
import com.angatusistemas.whatsapp.request.EditMessageRequest;
import com.angatusistemas.whatsapp.request.SendAudioRequest;
import com.angatusistemas.whatsapp.request.SendContactRequest;
import com.angatusistemas.whatsapp.request.SendDocumentRequest;
import com.angatusistemas.whatsapp.request.SendImageRequest;
import com.angatusistemas.whatsapp.request.SendLocationRequest;
import com.angatusistemas.whatsapp.request.SendReactionRequest;
import com.angatusistemas.whatsapp.request.SendStickerRequest;
import com.angatusistemas.whatsapp.request.SendVideoRequest;
import com.angatusistemas.whatsapp.runtime.BridgeProcessManager;
import com.angatusistemas.whatsapp.transport.BridgeConnection;
import com.angatusistemas.whatsapp.transport.DisconnectInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Facade for one WhatsApp connection. Each {@code WhatsAppClient} owns its
 * own bridge OS process (see {@link BridgeProcessManager}) rather than
 * sharing one process across every client in the JVM: the bridge's
 * {@code InstanceManager} does support multiple instances per process, but
 * running one process per client gives fault isolation (a crash in one
 * instance's Baileys socket cannot take down every other WhatsApp number an
 * application manages) at the cost of one extra Node process per connected
 * number - a reasonable trade for what this SDK is for. See README
 * "Arquitetura" for more, and "Proximas melhorias" for a possible opt-in
 * shared-process mode for very high instance counts.
 *
 * <p>Not reusable across a full lifecycle: once {@link #close()} is called,
 * build a new client to reconnect. {@link #connect()}/{@link #disconnect()}
 * on the other hand can be called repeatedly on the same instance.
 */
public final class WhatsAppClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppClient.class);

    private static final Duration BRIDGE_READY_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration SHUTDOWN_REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration SHUTDOWN_GRACE_PERIOD = Duration.ofSeconds(10);
    private static final Duration DEFAULT_MIN_RECONNECT_DELAY = Duration.ofSeconds(1);
    private static final Duration DEFAULT_MAX_RECONNECT_DELAY = Duration.ofSeconds(30);
    private static final int DEFAULT_MAX_RECONNECT_ATTEMPTS = 10;
    private static final Duration SEND_REQUEST_TIMEOUT = Duration.ofSeconds(120);

    /**
     * Teto do conjunto {@link #presencaAssinada}.
     *
     * <p>Sem ele, um processo de longa duracao acumula um {@code String} por
     * conversa distinta que ja escreveu, para sempre. Vinte mil cabe folgado em
     * memoria e cobre qualquer volume plausivel de conversas ativas; ao encher,
     * o conjunto e esvaziado por inteiro - o mesmo padrao "capado, e ao encher,
     * limpa tudo" que o bridge ja usa em {@code LidDirectory},
     * {@code numerosVerificados} e {@code gruposEmCache}.
     */
    private static final int MAX_PRESENCAS_ASSINADAS = 20_000;

    /**
     * Intervalo entre tentativas depois que o backoff exponencial se esgotou.
     *
     * <p>Ver {@link Builder#reconnectCooldown(Duration)} para o porque de isto
     * existir. Cinco minutos: devagar o bastante para nao martelar nada, rapido
     * o bastante para o numero voltar sozinho dentro do mesmo turno de trabalho.
     */
    private static final Duration DEFAULT_RECONNECT_COOLDOWN = Duration.ofMinutes(5);

    private final String instanceId;
    private final Path sessionDirectory;
    private final String displayName;
    private final Boolean markOnlineOnConnect;
    private final Duration minReconnectDelay;
    private final Duration maxReconnectDelay;
    private final int maxReconnectAttempts;
    /** {@code null} = desistir de vez apos {@link #maxReconnectAttempts} (comportamento antigo). */
    private final Duration reconnectCooldown;
    private final String nodeVersion;
    private final String bridgeHost;
    private final Integer bridgePort;
    private final String logLevel;

    private final List<MessageListener> messageListeners = new CopyOnWriteArrayList<>();
    private final List<MessageUpdatedListener> messageUpdatedListeners = new CopyOnWriteArrayList<>();
    private final List<MessageDeletedListener> messageDeletedListeners = new CopyOnWriteArrayList<>();
    private final List<ReactionListener> reactionListeners = new CopyOnWriteArrayList<>();
    private final List<ConnectionStateListener> connectionStateListeners = new CopyOnWriteArrayList<>();
    private final List<QrCodeListener> qrCodeListeners = new CopyOnWriteArrayList<>();
    private final List<GroupUpdateListener> groupUpdateListeners = new CopyOnWriteArrayList<>();
    private final List<PresenceListener> presenceListeners = new CopyOnWriteArrayList<>();
    private final List<ErrorListener> errorListeners = new CopyOnWriteArrayList<>();

    private final ScheduledExecutorService reconnectExecutor =
            Executors.newSingleThreadScheduledExecutor(this::newReconnectThread);
    private final AtomicReference<ConnectionState> state = new AtomicReference<>(ConnectionState.DISCONNECTED);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile boolean userInitiatedStop = true;
    private volatile String currentQr;

    private volatile BridgeProcessManager processManager;
    private volatile BridgeConnection connection;

    /**
     * Trava contra duas tentativas simultaneas de criar processo/WebSocket.
     *
     * <p>Sem ela, duas chamadas concorrentes que cheguem a
     * {@link #startBridgeAndConnectInstance()} sobem dois processos do bridge e
     * so a referencia do segundo sobrevive em {@link #processManager} - o
     * primeiro fica orfao, vivo, segurando a trava de sessao em disco e duas
     * threads nao-daemon da biblioteca de WebSocket.
     */
    private final AtomicBoolean orchestratingConnection = new AtomicBoolean(false);

    /**
     * A fila de envio desta instancia: uma thread, nada em paralelo, com o
     * intervalo humano e o "digitando..." embutidos. Ver {@link SendPacer}.
     */
    private final SendPacer pacer;
    /** Conversas cuja presenca ja foi assinada, para nao repetir a consulta. */
    private final java.util.Set<String> presencaAssinada = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private WhatsAppClient(Builder builder) {
        this.instanceId = builder.instanceId;
        this.sessionDirectory = builder.sessionDirectory;
        this.displayName = builder.displayName;
        this.markOnlineOnConnect = builder.markOnlineOnConnect;
        this.minReconnectDelay = builder.minReconnectDelay != null ? builder.minReconnectDelay : DEFAULT_MIN_RECONNECT_DELAY;
        this.maxReconnectDelay = builder.maxReconnectDelay != null ? builder.maxReconnectDelay : DEFAULT_MAX_RECONNECT_DELAY;
        this.maxReconnectAttempts = builder.maxReconnectAttempts != null ? builder.maxReconnectAttempts : DEFAULT_MAX_RECONNECT_ATTEMPTS;
        this.reconnectCooldown = builder.reconnectCooldownSet ? builder.reconnectCooldown : DEFAULT_RECONNECT_COOLDOWN;
        this.nodeVersion = builder.nodeVersion;
        this.bridgeHost = builder.bridgeHost;
        this.bridgePort = builder.bridgePort;
        this.logLevel = builder.logLevel;
        // O registro vem ANTES do pacer de proposito. SendPacer sobe a thread
        // da fila no proprio construtor; se register() lancar (instanceId ou
        // sessionDirectory repetido), o construtor do WhatsAppClient propaga a
        // excecao sem devolver objeto nenhum - e ninguem teria como chamar
        // pacer.close() na thread que acabou de nascer.
        InstanceRegistry.register(instanceId, sessionDirectory);

        this.pacer = new SendPacer(instanceId, new PacerBackendImpl(),
                builder.pacing != null ? builder.pacing : PacingOptions.padrao(),
                builder.humanization != null ? builder.humanization : HumanizationOptions.padrao());
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Conecta ao WhatsApp, reaproveitando o processo do bridge e o WebSocket
     * que ja existirem.
     *
     * <p>Os tres caminhos, do mais barato ao mais caro, sao os mesmos que
     * {@link #attemptReconnect(boolean)} ja escolhia internamente. Chamar
     * {@code connect()} depois de {@link #disconnect()} - o ciclo de pausar e
     * retomar documentado em EXAMPLES.md - cai no primeiro: {@code disconnect()}
     * fecha a conexao do lado do WhatsApp e nao toca no processo nem no
     * WebSocket entre Java e bridge, que continuam validos. Antes, este metodo
     * subia um processo novo em toda chamada; o antigo, ainda vivo, ficava sem
     * referencia nenhuma e ainda derrubava o novo com
     * {@code INSTANCE_ALREADY_EXISTS} - a trava de sessao em disco pertencia a
     * ele.
     *
     * <p>O caminho rapido nao precisa de trava propria: o {@code connect()} do
     * lado do bridge ja e no-op quando existe socket ativo (uma conta, um
     * socket).
     */
    public CompletableFuture<Void> connect() {
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("WhatsAppClient '" + instanceId + "' ja foi fechado (close())"));
        }
        userInitiatedStop = false;
        reconnectAttempts.set(0);

        BridgeConnection existingConnection = this.connection;
        if (existingConnection != null && existingConnection.isOpen()) {
            return existingConnection.sendRequest("connect", instanceId, JsonNodeFactory.instance.objectNode())
                    .thenApply(v -> (Void) null);
        }
        BridgeProcessManager existingProcess = this.processManager;
        if (existingProcess != null && existingProcess.isAlive()) {
            return reconnectWebSocketOnly();
        }
        return startBridgeAndConnectInstance();
    }

    public CompletableFuture<Void> disconnect() {
        userInitiatedStop = true;
        BridgeConnection current = this.connection;
        if (current == null || !current.isOpen()) {
            return CompletableFuture.completedFuture(null);
        }
        return current.sendRequest("disconnect", instanceId, JsonNodeFactory.instance.objectNode()).thenApply(v -> null);
    }

    public CompletableFuture<Void> logout() {
        userInitiatedStop = true;
        BridgeConnection current = this.connection;
        if (current == null || !current.isOpen()) {
            return CompletableFuture.failedFuture(new ConnectionException(instanceId, BridgeErrorCode.NOT_CONNECTED,
                    "nao e possivel fazer logout: WhatsAppClient '" + instanceId + "' nao esta conectado ao bridge"));
        }
        return current.sendRequest("logout", instanceId, JsonNodeFactory.instance.objectNode()).thenApply(v -> null);
    }

    public CompletableFuture<String> requestPairingCode(String phoneNumber) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("phoneNumber", Objects.requireNonNull(phoneNumber, "phoneNumber"));
        return connection().sendRequest("request_pairing_code", instanceId, payload)
                .thenApply(data -> data.path("code").asText(null));
    }

    /**
     * Closes sessions, closes the WebSocket, asks the bridge to shut down,
     * waits for it to exit, and force-kills it if it does not - synchronous
     * by design (unlike the rest of this API), matching {@link AutoCloseable}
     * convention. Safe to call more than once; only the first call does
     * anything. A JVM shutdown hook is not registered as a substitute for
     * calling this explicitly - see README "Encerramento".
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        userInitiatedStop = true;
        InstanceRegistry.unregister(instanceId);
        log.info("encerrando WhatsAppClient '{}'", instanceId);

        // Antes de derrubar a conexao: o que esta na fila nao vai sair, e cada
        // item precisa completar o future de quem o espera. Sem isto, quem
        // chamou send*() e esta bloqueado em .get() ficaria pendurado para
        // sempre depois de um close().
        pacer.close();

        BridgeConnection currentConnection = this.connection;
        BridgeProcessManager currentProcess = this.processManager;

        if (currentConnection != null && currentConnection.isOpen()) {
            try {
                currentConnection.sendRequest("shutdown", null, JsonNodeFactory.instance.objectNode())
                        .get(SHUTDOWN_REQUEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            } catch (Exception e) {
                log.debug("pedido de shutdown educado ao bridge nao respondeu a tempo, prosseguindo com termino forcado: {}",
                        e.getMessage());
            }
            try {
                currentConnection.whenClosed().get(SHUTDOWN_GRACE_PERIOD.toSeconds(), TimeUnit.SECONDS);
            } catch (Exception e) {
                log.debug("conexao com o bridge nao fechou a tempo apos o pedido de shutdown: {}", e.getMessage());
            }
        }
        // Fora do if de proposito: o caminho educado acima pode ter esgotado o
        // timeout com a conexao ainda aberta, e ai ela tambem precisa ser
        // fechada. close() e idempotente, entao chamar sempre nao custa nada e
        // garante que as threads da biblioteca de WebSocket caiam.
        if (currentConnection != null) {
            currentConnection.close();
        }

        if (currentProcess != null && !currentProcess.stop(SHUTDOWN_GRACE_PERIOD)) {
            log.warn("nao foi possivel confirmar o encerramento do processo do bridge de '{}' - "
                    + "pode ter restado um processo node orfao", instanceId);
        }

        reconnectExecutor.shutdownNow();
    }

    // ---------------------------------------------------------------- state

    public String getInstanceId() {
        return instanceId;
    }

    public ConnectionState getConnectionState() {
        return state.get();
    }

    public String getCurrentQrCode() {
        return currentQr;
    }

    /** Renders the current QR code (300x300); null if none is currently available. */
    public byte[] getQrCodePng() {
        return getQrCodePng(300);
    }

    public byte[] getQrCodePng(int sizePixels) {
        String qr = this.currentQr;
        return qr == null ? null : QrCodeRenderer.renderPng(qr, sizePixels);
    }

    // ---------------------------------------------------------------- listeners

    public void addMessageListener(MessageListener listener) {
        messageListeners.add(Objects.requireNonNull(listener));
    }

    public void addMessageUpdatedListener(MessageUpdatedListener listener) {
        messageUpdatedListeners.add(Objects.requireNonNull(listener));
    }

    public void addMessageDeletedListener(MessageDeletedListener listener) {
        messageDeletedListeners.add(Objects.requireNonNull(listener));
    }

    public void addReactionListener(ReactionListener listener) {
        reactionListeners.add(Objects.requireNonNull(listener));
    }

    public void addConnectionStateListener(ConnectionStateListener listener) {
        connectionStateListeners.add(Objects.requireNonNull(listener));
    }

    public void addQrCodeListener(QrCodeListener listener) {
        qrCodeListeners.add(Objects.requireNonNull(listener));
    }

    public void addGroupUpdateListener(GroupUpdateListener listener) {
        groupUpdateListeners.add(Objects.requireNonNull(listener));
    }

    public void addPresenceListener(PresenceListener listener) {
        presenceListeners.add(Objects.requireNonNull(listener));
    }

    public void addErrorListener(ErrorListener listener) {
        errorListeners.add(Objects.requireNonNull(listener));
    }

    // ---------------------------------------------------------------- sending

    /**
     * Poe um envio na fila desta instancia.
     *
     * <p><b>Mudanca de comportamento que vale registrar:</b> antes, um envio com
     * o cliente desconectado lancava {@link ConnectionException} na thread de
     * quem chamou, enquanto qualquer outra falha vinha pelo {@code future} —
     * uma inconsistencia que escapava de quem so envolvia tudo em
     * {@code .exceptionally(...)}. Agora {@code connection()} e avaliado dentro
     * da fila, entao <b>toda</b> falha chega pelo future.
     */
    private CompletableFuture<String> enfileirar(String to, QueuedSend.Kind kind, int textLength,
                                                 String action, ObjectNode payload) {
        return pacer.enqueue(to, kind, textLength,
                () -> connection().sendRequest(action, instanceId, payload, SEND_REQUEST_TIMEOUT)
                        .thenApply(WhatsAppClient::extractMessageId)).result();
    }

    private static int tamanho(String texto) {
        return texto == null ? 0 : texto.length();
    }

    /** O que a fila precisa do cliente, sem que o {@link SendPacer} dependa dele. */
    private final class PacerBackendImpl implements SendPacer.PacerBackend {
        @Override
        public CompletableFuture<Void> sendPresence(String chatId, String presence) {
            return WhatsAppClient.this.sendPresence(chatId, presence);
        }

        @Override
        public CompletableFuture<Void> markChatRead(String chatId) {
            return WhatsAppClient.this.markChatRead(chatId);
        }
    }

    public CompletableFuture<String> sendText(String to, String text) {
        return sendText(to, text, null);
    }

    public CompletableFuture<String> sendText(String to, String text, String quotedMessageId) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("to", Objects.requireNonNull(to, "to"));
        payload.put("text", Objects.requireNonNull(text, "text"));
        putIfPresent(payload, "quotedMessageId", quotedMessageId);
        return enfileirar(to, QueuedSend.Kind.TEXT, text.length(), "send_text", payload);
    }

    public CompletableFuture<String> sendImage(SendImageRequest request) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("to", request.to());
        payload.put("filePath", request.filePath());
        putIfPresent(payload, "caption", request.caption());
        putIfPresent(payload, "quotedMessageId", request.quotedMessageId());
        return enfileirar(request.to(), QueuedSend.Kind.MEDIA, tamanho(request.caption()), "send_image", payload);
    }

    public CompletableFuture<String> sendVideo(SendVideoRequest request) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("to", request.to());
        payload.put("filePath", request.filePath());
        putIfPresent(payload, "caption", request.caption());
        payload.put("gifPlayback", request.gifPlayback());
        putIfPresent(payload, "quotedMessageId", request.quotedMessageId());
        return enfileirar(request.to(), QueuedSend.Kind.MEDIA, tamanho(request.caption()), "send_video", payload);
    }

    public CompletableFuture<String> sendAudio(SendAudioRequest request) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("to", request.to());
        payload.put("filePath", request.filePath());
        payload.put("ptt", request.ptt());
        putIfPresent(payload, "quotedMessageId", request.quotedMessageId());
        return enfileirar(request.to(), QueuedSend.Kind.AUDIO, 0, "send_audio", payload);
    }

    public CompletableFuture<String> sendDocument(SendDocumentRequest request) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("to", request.to());
        payload.put("filePath", request.filePath());
        payload.put("fileName", request.fileName());
        payload.put("mimetype", request.mimetype());
        putIfPresent(payload, "caption", request.caption());
        putIfPresent(payload, "quotedMessageId", request.quotedMessageId());
        return enfileirar(request.to(), QueuedSend.Kind.MEDIA, tamanho(request.caption()), "send_document", payload);
    }

    public CompletableFuture<String> sendSticker(SendStickerRequest request) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("to", request.to());
        payload.put("filePath", request.filePath());
        putIfPresent(payload, "quotedMessageId", request.quotedMessageId());
        return enfileirar(request.to(), QueuedSend.Kind.MEDIA, 0, "send_sticker", payload);
    }

    public CompletableFuture<String> sendLocation(SendLocationRequest request) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("to", request.to());
        payload.put("latitude", request.latitude());
        payload.put("longitude", request.longitude());
        putIfPresent(payload, "name", request.name());
        putIfPresent(payload, "address", request.address());
        putIfPresent(payload, "quotedMessageId", request.quotedMessageId());
        return enfileirar(request.to(), QueuedSend.Kind.MEDIA, 0, "send_location", payload);
    }

    public CompletableFuture<String> sendContact(SendContactRequest request) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("to", request.to());
        payload.put("displayName", request.displayName());
        payload.put("phoneNumber", request.phoneNumber());
        putIfPresent(payload, "organization", request.organization());
        putIfPresent(payload, "quotedMessageId", request.quotedMessageId());
        return enfileirar(request.to(), QueuedSend.Kind.MEDIA, 0, "send_contact", payload);
    }

    /**
     * Replaces the text of a message already sent by this instance.
     *
     * <p>WhatsApp keeps an edit window of a few minutes after delivery and
     * does not publish the exact limit; past it the server refuses and the
     * future completes exceptionally. There is no way to check in advance, so
     * treat the refusal as the answer rather than as an unexpected error.
     *
     * @param request target message and its new text
     * @return id of the edit message the server accepted
     */
    public CompletableFuture<String> editMessage(EditMessageRequest request) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("chatId", request.chatId());
        payload.put("messageId", request.messageId());
        payload.put("text", request.text());
        payload.put("fromMe", request.fromMe());
        putIfPresent(payload, "participant", request.participant());
        return connection().sendRequest("edit_message", instanceId, payload, SEND_REQUEST_TIMEOUT).thenApply(WhatsAppClient::extractMessageId);
    }

    /**
     * Revokes a message for everyone ("delete for everyone").
     *
     * <p>Same window caveat as {@link #editMessage(EditMessageRequest)}.
     *
     * @param request target message
     * @return future completed when the server accepts the revoke
     */
    public CompletableFuture<Void> deleteMessage(DeleteMessageRequest request) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("chatId", request.chatId());
        payload.put("messageId", request.messageId());
        payload.put("fromMe", request.fromMe());
        putIfPresent(payload, "participant", request.participant());
        return connection().sendRequest("delete_message", instanceId, payload).thenApply(v -> null);
    }

    public CompletableFuture<Void> sendReaction(SendReactionRequest request) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("chatId", request.chatId());
        payload.put("messageId", request.messageId());
        putIfPresent(payload, "emoji", request.emoji());
        payload.put("fromMe", request.fromMe());
        putIfPresent(payload, "participant", request.participant());
        return connection().sendRequest("send_reaction", instanceId, payload).thenApply(v -> null);
    }

    /**
     * Publishes our own presence in a chat: {@code composing} shows the
     * recipient the "typing..." indicator, {@code recording} the voice-note
     * one, {@code paused} clears it, and {@code available}/{@code unavailable}
     * set online/offline.
     *
     * Presence is decoration around a message, never the message itself:
     * callers are expected to treat a failure here as cosmetic and send
     * anyway.
     *
     * @param chatId conversation to publish the presence in
     * @param presence one of {@code unavailable}, {@code available},
     *        {@code composing}, {@code recording}, {@code paused}
     * @return future completed when the bridge acknowledges the update
     */
    public CompletableFuture<Void> sendPresence(String chatId, String presence) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("chatId", Objects.requireNonNull(chatId, "chatId"));
        payload.put("presence", Objects.requireNonNull(presence, "presence"));
        return connection().sendRequest("send_presence", instanceId, payload).thenApply(v -> null);
    }

    /**
     * Marca uma mensagem recebida como lida — o "azulzinho" no celular de quem
     * escreveu.
     *
     * <p>A mensagem precisa ter sido vista por este processo e ainda estar no
     * cache do bridge: o WhatsApp exige a chave completa da mensagem
     * ({@code remoteJid}, {@code id}, {@code participant}, {@code fromMe}), e
     * inventar uma a partir do id faria o stanza ser aceito sem o azul nunca
     * aparecer — falha silenciosa, que e pior que erro.
     *
     * <p>Se a conta estiver com "confirmacoes de leitura" desligada na
     * privacidade do celular, a marcacao e aceita mas o remetente nao ve o azul.
     * Isso nao depende da SDK.
     *
     * @param chatId conversa
     * @param messageId mensagem a marcar
     * @return future completado quando o bridge confirmar
     * @see HumanizationOptions#readReceipts()
     */
    public CompletableFuture<Void> markRead(String chatId, String messageId) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("chatId", Objects.requireNonNull(chatId, "chatId"));
        payload.put("messageId", Objects.requireNonNull(messageId, "messageId"));
        return connection().sendRequest("mark_read", instanceId, payload).thenApply(v -> null);
    }

    /**
     * Marca a <b>ultima mensagem recebida</b> de uma conversa como lida.
     *
     * <p>E o que o modo {@link ReadReceiptMode#ON_REPLY} usa antes de enviar uma
     * resposta. Falha se nenhuma mensagem daquela conversa foi vista por este
     * processo.
     *
     * @param chatId conversa
     * @return future completado quando o bridge confirmar
     */
    public CompletableFuture<Void> markChatRead(String chatId) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("chatId", Objects.requireNonNull(chatId, "chatId"));
        return connection().sendRequest("mark_read", instanceId, payload).thenApply(v -> null);
    }

    /**
     * Assina a presenca de uma conversa, para o {@link PresenceListener} passar
     * a receber o "digitando..." e o online <b>do contato</b>.
     *
     * <p>Sem esta assinatura o WhatsApp simplesmente nao envia presenca daquela
     * conversa. Ate esta versao ela so acontecia como efeito colateral de
     * publicar a nossa propria presenca, o que tornava impossivel observar um
     * contato sem antes escrever para ele.
     *
     * <p>Chame nas conversas que importam, e nao em todas: cada assinatura e uma
     * consulta, e leitura em volume e o que faz uma conta ser suspensa por
     * coleta. Para assinar automaticamente toda conversa que escrever, ha
     * {@code HumanizationOptions.builder().autoSubscribePresence(true)}.
     *
     * @param chatId conversa a observar
     * @return future completado quando o bridge confirmar
     */
    public CompletableFuture<Void> subscribePresence(String chatId) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("chatId", Objects.requireNonNull(chatId, "chatId"));
        return connection().sendRequest("subscribe_presence", instanceId, payload).thenApply(v -> null);
    }

    // ---------------------------------------------------------------- fila de envio

    /** O ritmo em vigor nesta instancia. */
    public PacingOptions pacing() {
        return pacer.pacing();
    }

    /** Os sinais de humanizacao em vigor nesta instancia. */
    public HumanizationOptions humanization() {
        return pacer.humanization();
    }

    /**
     * Troca o ritmo em tempo de execucao; vale do proximo envio em diante.
     *
     * <p>Para quando uma campanha precisa de uma cadencia propria e o resto do
     * sistema, de outra.
     *
     * @param pacing novo ritmo, ou {@code null} para voltar ao padrao
     */
    public void setPacing(PacingOptions pacing) {
        pacer.setPacing(pacing);
    }

    /**
     * Troca os sinais de humanizacao em tempo de execucao.
     *
     * @param humanization nova configuracao, ou {@code null} para o padrao
     */
    public void setHumanization(HumanizationOptions humanization) {
        pacer.setHumanization(humanization);
    }

    /**
     * Envios que ainda nao sairam, incluindo o que esta em andamento.
     *
     * <p>Para a aplicacao mostrar o que esta na fila e dar ao operador um botao
     * de parar.
     */
    public List<QueuedSend> pendingSends() {
        return pacer.pending();
    }

    /**
     * Cancela um envio que ainda nao comecou a sair.
     *
     * @param queuedSendId o {@link QueuedSend#id()}
     * @return {@code true} se ele estava na fila e foi removido
     */
    public boolean cancelSend(String queuedSendId) {
        return pacer.cancel(queuedSendId);
    }

    /**
     * Esvazia a fila. O envio em andamento termina normalmente — aborta-lo no
     * meio deixaria a duvida de se a mensagem saiu.
     *
     * @return quantos envios foram descartados
     */
    public int clearQueue() {
        return pacer.clear();
    }

    // ---------------------------------------------------------------- media / contacts / groups

    public CompletableFuture<MediaContent> downloadMedia(String chatId, String messageId) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("chatId", Objects.requireNonNull(chatId, "chatId"));
        payload.put("messageId", Objects.requireNonNull(messageId, "messageId"));
        return connection().sendRequest("download_media", instanceId, payload)
                .thenApply(data -> new MediaContent(
                        Base64.getDecoder().decode(data.path("base64").asText("")),
                        data.path("mimetype").asText("application/octet-stream")));
    }

    /**
     * Identity of the account this instance is logged in as.
     *
     * Only the pairing-code flow tells the caller which number it asked to
     * connect; after a QR-code login nothing else reveals it, so this is the
     * only way to label a connected session with its own phone number.
     *
     * @return {@code { jid, phoneNumber, name }} — any field may be null when
     *         WhatsApp did not provide it
     */
    public CompletableFuture<JsonNode> getMe() {
        return connection().sendRequest("get_me", instanceId, JsonNodeFactory.instance.objectNode());
    }

    /**
     * Se um numero tem conta no WhatsApp — <b>uma</b> consulta, com cache.
     *
     * <p>E o que usar antes de enviar para um numero novo. Prefira sempre este
     * metodo a {@link #getContact(String)}: aquele dispara <b>tres</b> consultas
     * (existencia, foto e recado pessoal) e so a primeira interessa aqui. Ler
     * foto e recado de gente com quem nao se esta conversando, em volume, e
     * exatamente o comportamento que faz o WhatsApp suspender uma conta por
     * coleta de dados.
     *
     * <p>A resposta fica em cache no bridge: 24h para um numero que existe, 6h
     * para um que nao existe (menor, para um numero recem-criado nao ficar
     * condenado o dia todo). Repetir a mesma pergunta sobre o mesmo numero
     * tambem e um padrao de sondagem, entao o cache protege duas vezes.
     *
     * <p><b>Envie para o JID que voltar daqui</b>, e nao para o numero que voce
     * digitou: e o servidor do WhatsApp quem sabe se a conta e a com ou a sem o
     * nono digito.
     *
     * @param number numero em digitos, com DDI (ex.: {@code "5562999998888"})
     * @return existencia e o JID canonico
     */
    public CompletableFuture<NumberCheck> checkNumber(String number) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("number", Objects.requireNonNull(number, "number"));
        return connection().sendRequest("check_number", instanceId, payload).thenApply(NumberCheck::fromJson);
    }

    /**
     * Contato completo: existencia, foto e recado pessoal.
     *
     * <p><b>Custa tres consultas ao WhatsApp por chamada</b> — {@code onWhatsApp},
     * {@code profilePictureUrl} e {@code fetchStatus} — e por isso pesa tres
     * vezes no teto de leitura do bridge.
     *
     * <p>Foi este metodo, chamado em lista, que fez uma aplicacao ter a conta
     * suspensa: uma tela pedia o avatar de 200 conversas ao abrir, o que virava
     * ate 600 consultas em rajada logo apos conectar, sondando existencia de
     * numero e lendo o recado pessoal de gente com quem ninguem estava
     * conversando.
     *
     * <p>Quase sempre o que se quer e um dos dois metodos baratos:
     * {@link #checkNumber(String)} (so existencia) ou
     * {@link #getProfilePicture(String)} (so a foto).
     *
     * @param jid contato
     * @return o contato, com os campos que o WhatsApp devolveu
     */
    public CompletableFuture<Contact> getContact(String jid) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("jid", Objects.requireNonNull(jid, "jid"));
        return connection().sendRequest("get_contact", instanceId, payload).thenApply(Contact::fromJson);
    }

    /**
     * URL da foto de perfil de um contato ou grupo, e nada alem disso.
     *
     * Prefira este metodo a {@link #getContact(String)} sempre que o objetivo
     * for so exibir um avatar. O {@code getContact} faz TRES consultas ao
     * WhatsApp por chamada — existencia do numero, foto e o recado pessoal do
     * contato — e uma tela que mostra a foto de uma lista de contatos
     * transforma isso numa varredura da agenda, lendo dados de terceiros que
     * ela nao exibe. Aqui e uma consulta so, do unico dado que sera mostrado.
     *
     * @param jid contato ou grupo
     * @return {@code { jid, profilePictureUrl }}, com a URL nula quando nao ha
     *         foto ou ela nao esta visivel para esta conta
     */
    public CompletableFuture<JsonNode> getProfilePicture(String jid) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("jid", Objects.requireNonNull(jid, "jid"));
        return connection().sendRequest("get_profile_picture", instanceId, payload);
    }

    public CompletableFuture<Group> getGroupMetadata(String groupJid) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("groupJid", Objects.requireNonNull(groupJid, "groupJid"));
        return connection().sendRequest("get_group_metadata", instanceId, payload).thenApply(Group::fromJson);
    }

    public CompletableFuture<List<Group>> listGroups() {
        return connection().sendRequest("list_groups", instanceId, JsonNodeFactory.instance.objectNode())
                .thenApply(data -> {
                    List<Group> groups = new ArrayList<>();
                    JsonNode groupsNode = data.get("groups");
                    if (groupsNode != null && groupsNode.isArray()) {
                        for (JsonNode groupNode : groupsNode) {
                            groups.add(Group.fromJson(groupNode));
                        }
                    }
                    return Collections.unmodifiableList(groups);
                });
    }

    public CompletableFuture<Group> createGroup(String subject, List<String> participants) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("subject", Objects.requireNonNull(subject, "subject"));
        ArrayNode participantsArray = payload.putArray("participants");
        Objects.requireNonNull(participants, "participants").forEach(participantsArray::add);
        return connection().sendRequest("create_group", instanceId, payload).thenApply(Group::fromJson);
    }

    public CompletableFuture<JsonNode> updateGroupParticipants(String groupJid, List<String> participants, GroupParticipantAction action) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("groupJid", Objects.requireNonNull(groupJid, "groupJid"));
        ArrayNode participantsArray = payload.putArray("participants");
        Objects.requireNonNull(participants, "participants").forEach(participantsArray::add);
        payload.put("action", Objects.requireNonNull(action, "action").name().toLowerCase(Locale.ROOT));
        return connection().sendRequest("update_group_participants", instanceId, payload).thenApply(data -> data.get("result"));
    }

    public CompletableFuture<Void> updateGroupSubject(String groupJid, String subject) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("groupJid", Objects.requireNonNull(groupJid, "groupJid"));
        payload.put("subject", Objects.requireNonNull(subject, "subject"));
        return connection().sendRequest("update_group_subject", instanceId, payload).thenApply(v -> null);
    }

    public CompletableFuture<Void> updateGroupDescription(String groupJid, String description) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("groupJid", Objects.requireNonNull(groupJid, "groupJid"));
        payload.put("description", Objects.requireNonNull(description, "description"));
        return connection().sendRequest("update_group_description", instanceId, payload).thenApply(v -> null);
    }

    public CompletableFuture<Void> leaveGroup(String groupJid) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("groupJid", Objects.requireNonNull(groupJid, "groupJid"));
        return connection().sendRequest("leave_group", instanceId, payload).thenApply(v -> null);
    }

    public CompletableFuture<String> getGroupInviteCode(String groupJid) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("groupJid", Objects.requireNonNull(groupJid, "groupJid"));
        return connection().sendRequest("get_group_invite_code", instanceId, payload)
                .thenApply(data -> data.hasNonNull("code") ? data.path("code").asText() : null);
    }

    public enum GroupParticipantAction {
        ADD,
        REMOVE,
        PROMOTE,
        DEMOTE
    }

    // ---------------------------------------------------------------- connection orchestration

    /**
     * Sobe um processo do bridge novo e conecta a instancia nele.
     *
     * <p>Protegido pela trava de orquestracao: junto de
     * {@link #reconnectWebSocketOnly()}, e o unico lugar que cria recursos de
     * sistema operacional. As duas entradas travam aqui dentro - e nao em quem
     * chama - para que toda chamada fique coberta, venha ela de
     * {@link #connect()} ou de {@link #attemptReconnect(boolean)}.
     */
    private CompletableFuture<Void> startBridgeAndConnectInstance() {
        if (!orchestratingConnection.compareAndSet(false, true)) {
            return jaHaConexaoEmAndamento();
        }
        return subirBridgeEConectar().whenComplete((v, ex) -> orchestratingConnection.set(false));
    }

    /** Corpo de {@link #startBridgeAndConnectInstance()}, ja com a trava tomada. */
    private CompletableFuture<Void> subirBridgeEConectar() {
        BridgeProcessManager newProcessManager;
        try {
            newProcessManager = BridgeProcessManager.start(
                    new BridgeProcessManager.StartOptions(bridgeHost, bridgePort, logLevel, nodeVersion));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        // O anterior so cai depois que o novo subiu: se start() falhar, nao vale
        // a pena derrubar o que ainda estava de pe.
        encerrarProcessoSubstituido(this.processManager);
        this.processManager = newProcessManager;

        return newProcessManager.portReady()
                .orTimeout(BRIDGE_READY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .thenCompose(port -> openConnection(newProcessManager, port))
                .thenCompose(ack -> sendCreateInstance())
                .thenCompose(v -> connection().sendRequest("connect", instanceId, JsonNodeFactory.instance.objectNode()))
                .thenApply(v -> (Void) null)
                .exceptionallyCompose(this::abortStartupAndPropagate);
    }

    private CompletableFuture<ProtocolAck> openConnection(BridgeProcessManager processManagerForThisAttempt, int port) {
        BridgeConnection newConnection = new BridgeConnection(URI.create("ws://127.0.0.1:" + port), processManagerForThisAttempt.authToken());
        newConnection.setEventListener(this::handleBridgeEvent);
        BridgeConnection previousConnection = this.connection;
        this.connection = newConnection;
        encerrarConexaoSubstituida(previousConnection);
        return newConnection.connect(HANDSHAKE_TIMEOUT).thenApply(ack -> {
            newConnection.whenClosed().thenAccept(info -> handleConnectionClosed(newConnection, info));
            return new ProtocolAck();
        });
    }

    /**
     * Fecha uma conexao que acaba de ser substituida por outra.
     *
     * <p>Diferente do processo, isto e inline: {@code close()} nao bloqueia em
     * E/S e e idempotente. A troca de {@link #connection} vem antes da chamada
     * de proposito - assim {@link #handleConnectionClosed} ja enxerga a nova
     * como a atual e reconhece este fechamento como o da anterior, sem agendar
     * uma reconexao que nao faz falta.
     */
    private void encerrarConexaoSubstituida(BridgeConnection anterior) {
        if (anterior != null) {
            anterior.close();
        }
    }

    /**
     * Encerra um processo do bridge que acaba de ser substituido.
     *
     * <p>Fora da thread chamadora porque {@code stop()} pode levar ate
     * {@link #SHUTDOWN_GRACE_PERIOD} mais o tempo do termino forcado se o
     * processo ainda estiver vivo, e a tentativa de conexao nova nao pode
     * esperar por isso. Mesmo com o processo ja morto a chamada importa: e
     * {@code stop()} quem remove o shutdown hook da JVM, e este e o caminho
     * percorrido a cada recuperacao de {@code BRIDGE_CRASH} - sem a chamada,
     * cada queda do bridge deixava uma {@code Thread} registrada para sempre
     * dentro do processo Java, que e o de vida longa.
     */
    private void encerrarProcessoSubstituido(BridgeProcessManager anterior) {
        if (anterior == null) {
            return;
        }
        try {
            reconnectExecutor.execute(() -> anterior.stop(SHUTDOWN_GRACE_PERIOD));
        } catch (RejectedExecutionException e) {
            // Executor ja encerrado por um close() concorrente: melhor segurar
            // esta thread do que deixar um processo node orfao para tras.
            anterior.stop(SHUTDOWN_GRACE_PERIOD);
        }
    }

    private CompletableFuture<Void> jaHaConexaoEmAndamento() {
        return CompletableFuture.failedFuture(new IllegalStateException(
                "ja existe uma tentativa de conexao em andamento para '" + instanceId + "'"));
    }

    /** Marker return type so the connect chain above reads as a sequence of steps rather than juggling HandshakeAck. */
    private static final class ProtocolAck {
    }

    private CompletableFuture<Void> abortStartupAndPropagate(Throwable ex) {
        BridgeProcessManager pm = this.processManager;
        if (pm != null) {
            pm.stop(SHUTDOWN_GRACE_PERIOD);
        }
        Throwable cause = unwrap(ex);
        notifyConnectionState(ConnectionState.FAILED, DisconnectCause.UNKNOWN, cause.getMessage());
        return CompletableFuture.failedFuture(cause);
    }

    private CompletableFuture<JsonNode> sendCreateInstance() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("sessionDirectory", sessionDirectory.toString());
        putIfPresent(payload, "displayName", displayName);
        if (markOnlineOnConnect != null) {
            payload.put("markOnlineOnConnect", markOnlineOnConnect);
        }
        payload.put("minReconnectDelayMs", minReconnectDelay.toMillis());
        payload.put("maxReconnectDelayMs", maxReconnectDelay.toMillis());
        return connection().sendRequest("create_instance", instanceId, payload);
    }

    private void handleConnectionClosed(BridgeConnection closedConnection, DisconnectInfo info) {
        if (userInitiatedStop || closed.get()) {
            return;
        }
        if (closedConnection != this.connection) {
            // Conexao ja substituida por outra: este fechamento e o encerramento
            // esperado da anterior, nao a queda da atual - reagir a ele agendaria
            // uma reconexao por cima de uma conexao que esta de pe.
            log.debug("fechamento de uma conexao ja substituida de '{}' ignorado (code={})", instanceId, info.code());
            return;
        }
        boolean bridgeStillAlive = processManager != null && processManager.isAlive();
        DisconnectCause cause = bridgeStillAlive ? DisconnectCause.CONNECTION_LOST : DisconnectCause.BRIDGE_CRASH;
        notifyConnectionState(ConnectionState.DISCONNECTED, cause,
                "conexao com o bridge perdida (code=" + info.code() + ", reason=" + info.reason() + ", remote=" + info.remote() + ")");
        scheduleReconnect(bridgeStillAlive);
    }

    private void scheduleReconnect(boolean bridgeStillAlive) {
        if (userInitiatedStop || closed.get()) {
            return;
        }
        int attempt = reconnectAttempts.incrementAndGet();
        long delayMs;

        if (attempt > maxReconnectAttempts) {
            if (reconnectCooldown == null) {
                // Comportamento antigo, agora so sob pedido explicito
                // (reconnectCooldown(null)): desiste de vez.
                notifyConnectionState(ConnectionState.FAILED, DisconnectCause.UNKNOWN,
                        "numero maximo de tentativas de reconexao excedido (" + maxReconnectAttempts + ")");
                notifyError(new ConnectionException(instanceId, BridgeErrorCode.CONNECTION_FAILED,
                        "desistindo de reconectar ao bridge para '" + instanceId + "' apos " + maxReconnectAttempts + " tentativas"));
                return;
            }

            delayMs = reconnectCooldown.toMillis() + ThreadLocalRandom.current().nextLong(1_000);
            if (attempt == maxReconnectAttempts + 1) {
                // O aviso sai UMA vez, na virada. Repeti-lo a cada ciclo
                // encheria o log (e o listener de erro da aplicacao) de uma
                // linha identica a cada cinco minutos, para sempre.
                notifyConnectionState(ConnectionState.FAILED, DisconnectCause.UNKNOWN,
                        "backoff esgotado apos " + maxReconnectAttempts + " tentativas - novas tentativas a cada "
                                + reconnectCooldown.toSeconds() + "s");
                notifyError(new ConnectionException(instanceId, BridgeErrorCode.CONNECTION_FAILED,
                        "bridge de '" + instanceId + "' nao voltou apos " + maxReconnectAttempts
                                + " tentativas; seguindo em tentativas espacadas de " + reconnectCooldown.toSeconds() + "s"));
                log.warn("bridge de '{}' nao voltou apos {} tentativas - passando a tentar a cada {}s, indefinidamente",
                        instanceId, maxReconnectAttempts, reconnectCooldown.toSeconds());
            } else {
                log.info("nova tentativa de reconexao ao bridge para '{}' em {} ms (modo espacado)", instanceId, delayMs);
            }
        } else {
            long boundedExponent = Math.min(attempt - 1, 20);
            delayMs = Math.min(maxReconnectDelay.toMillis(), minReconnectDelay.toMillis() * (1L << boundedExponent))
                    + ThreadLocalRandom.current().nextLong(250);
            log.info("agendando reconexao ao bridge para '{}' em {} ms (tentativa {}/{})", instanceId, delayMs, attempt, maxReconnectAttempts);
        }

        reconnectExecutor.schedule(() -> attemptReconnect(bridgeStillAlive), delayMs, TimeUnit.MILLISECONDS);
    }

    private void attemptReconnect(boolean assumedBridgeStillAlive) {
        if (userInitiatedStop || closed.get()) {
            return;
        }
        long attemptTimeoutSeconds = BRIDGE_READY_TIMEOUT.toSeconds() + HANDSHAKE_TIMEOUT.toSeconds();
        try {
            boolean bridgeActuallyAlive = assumedBridgeStillAlive && processManager != null && processManager.isAlive();
            if (bridgeActuallyAlive) {
                reconnectWebSocketOnly().get(attemptTimeoutSeconds, TimeUnit.SECONDS);
            } else {
                startBridgeAndConnectInstance().get(attemptTimeoutSeconds, TimeUnit.SECONDS);
            }
            reconnectAttempts.set(0);
            log.info("reconexao ao bridge para '{}' bem-sucedida", instanceId);
        } catch (Exception e) {
            Throwable cause = unwrap(e);
            log.warn("tentativa de reconexao ao bridge para '{}' falhou: {}", instanceId, cause.getMessage());
            notifyError(cause instanceof WhatsAppException wae ? wae
                    : new BridgeException("tentativa de reconexao falhou para '" + instanceId + "'", cause));
            boolean stillAliveNow = processManager != null && processManager.isAlive();
            scheduleReconnect(stillAliveNow);
        }
    }

    /**
     * The bridge process survived; only the Java&lt;-&gt;bridge WebSocket needs to be
     * re-established, not the WhatsApp connection itself.
     *
     * <p>Sob a mesma trava de {@link #startBridgeAndConnectInstance()}: as duas
     * criam recursos que ninguem mais teria como fechar se ficassem sem
     * referencia.
     */
    private CompletableFuture<Void> reconnectWebSocketOnly() {
        if (!orchestratingConnection.compareAndSet(false, true)) {
            return jaHaConexaoEmAndamento();
        }
        return reabrirWebSocket().whenComplete((v, ex) -> orchestratingConnection.set(false));
    }

    /** Corpo de {@link #reconnectWebSocketOnly()}, ja com a trava tomada. */
    private CompletableFuture<Void> reabrirWebSocket() {
        BridgeProcessManager currentProcess = this.processManager;
        int port;
        try {
            port = currentProcess.portReady().getNow(-1);
        } catch (RuntimeException e) {
            port = -1;
        }
        if (port <= 0) {
            // Chama o corpo, nao o metodo travado: a trava ja e nossa.
            return subirBridgeEConectar();
        }

        BridgeConnection newConnection = new BridgeConnection(URI.create("ws://127.0.0.1:" + port), currentProcess.authToken());
        newConnection.setEventListener(this::handleBridgeEvent);
        BridgeConnection previousConnection = this.connection;
        this.connection = newConnection;
        encerrarConexaoSubstituida(previousConnection);
        return newConnection.connect(HANDSHAKE_TIMEOUT)
                .thenCompose(ack -> {
                    newConnection.whenClosed().thenAccept(info -> handleConnectionClosed(newConnection, info));
                    return newConnection.sendRequest("get_status", instanceId, JsonNodeFactory.instance.objectNode());
                })
                .thenAccept(status -> notifyConnectionState(
                        ConnectionState.fromWireValue(status.path("state").asText(null)),
                        null,
                        "estado resincronizado apos reconexao do websocket"));
    }

    // ---------------------------------------------------------------- event dispatch

    private void handleBridgeEvent(com.angatusistemas.whatsapp.protocol.ProtocolMessage.Event event) {
        try {
            JsonNode data = event.data();
            switch (event.event()) {
                case "connection_state_changed" -> handleConnectionStateChangedEvent(data);
                case "qr_code" -> handleQrCodeEvent(data);
                case "pairing_code" -> { /* delivered via requestPairingCode()'s own return value */ }
                case "message_received" -> notifyMessage(Message.fromJson(instanceId, data));
                case "message_updated" -> notifyMessageUpdated(data);
                case "message_deleted" -> notifyMessageDeleted(data);
                case "reaction_received" -> notifyReaction(data);
                case "group_updated" -> notifyGroupUpdated(data);
                case "presence_updated" -> notifyPresenceUpdated(data);
                default -> log.debug("evento desconhecido recebido do bridge para '{}': {}", instanceId, event.event());
            }
        } catch (RuntimeException e) {
            log.warn("falha ao processar evento '{}' do bridge para '{}': {}", event.event(), instanceId, e.getMessage(), e);
            notifyError(new BridgeException("falha ao processar evento '" + event.event() + "' do bridge", e));
        }
    }

    private void handleConnectionStateChangedEvent(JsonNode data) {
        ConnectionState newState = ConnectionState.fromWireValue(data.path("state").asText(null));
        DisconnectCause cause = data.hasNonNull("cause") ? DisconnectCause.fromWireValue(data.path("cause").asText()) : null;
        String detail = data.hasNonNull("detail") ? data.path("detail").asText() : null;
        if (newState != ConnectionState.QR_PENDING) {
            currentQr = null;
        }
        notifyConnectionState(newState, cause, detail);
    }

    private void handleQrCodeEvent(JsonNode data) {
        String qr = data.path("qr").asText(null);
        this.currentQr = qr;
        for (QrCodeListener listener : qrCodeListeners) {
            safeInvoke(() -> listener.onQrCodeReceived(qr));
        }
    }

    private void notifyConnectionState(ConnectionState newState, DisconnectCause cause, String detail) {
        state.set(newState);
        for (ConnectionStateListener listener : connectionStateListeners) {
            safeInvoke(() -> listener.onConnectionStateChanged(newState, cause, detail));
        }
    }

    private void notifyMessage(Message message) {
        reagirAMensagemRecebida(message);
        for (MessageListener listener : messageListeners) {
            safeInvoke(() -> listener.onMessageReceived(message));
        }
    }

    /**
     * Os sinais automaticos disparados pela chegada de uma mensagem: o
     * azulzinho no modo {@link ReadReceiptMode#AUTO} e a assinatura de presenca
     * da conversa.
     *
     * <p>Roda <b>fora</b> da thread de eventos do bridge. Essa thread e unica e
     * serve a todos os nove tipos de listener: segura-la por alguns segundos
     * esperando um atraso de leitura atrasaria tambem os eventos de conexao e de
     * QR Code da instancia inteira.
     */
    private void reagirAMensagemRecebida(Message message) {
        if (message == null || message.isFromMe() || message.chatId() == null) {
            return;
        }
        HumanizationOptions sinais = pacer.humanization();
        String chatId = message.chatId();

        if (sinais.autoSubscribePresence()) {
            if (presencaAssinada.size() >= MAX_PRESENCAS_ASSINADAS) {
                // Capado, e ao encher, limpa tudo - o mesmo padrao dos caches do
                // bridge. O pior caso e reassinar mais tarde uma conversa que ja
                // estava assinada, e subscribePresence e idempotente.
                log.debug("teto de {} presencas assinadas atingido em '{}' - recomecando o conjunto",
                        MAX_PRESENCAS_ASSINADAS, instanceId);
                presencaAssinada.clear();
            }
            if (presencaAssinada.add(chatId)) {
                subscribePresence(chatId).exceptionally(e -> {
                    // Nao conseguiu assinar: solta a marca para tentar de novo na
                    // proxima mensagem, em vez de desistir daquela conversa para
                    // sempre.
                    presencaAssinada.remove(chatId);
                    log.debug("nao foi possivel assinar a presenca de '{}': {}", chatId, e.getMessage());
                    return null;
                });
            }
        }

        if (sinais.readReceipts() != ReadReceiptMode.AUTO) {
            return;
        }
        String messageId = message.messageId();
        if (messageId == null) {
            return;
        }
        long atrasoMs = sinais.atrasoLeitura(ThreadLocalRandom.current()).toMillis();
        reconnectExecutor.schedule(() -> markRead(chatId, messageId).exceptionally(e -> {
            log.debug("nao foi possivel marcar '{}' como lida: {}", chatId, e.getMessage());
            return null;
        }), atrasoMs, TimeUnit.MILLISECONDS);
    }

    private void notifyMessageUpdated(JsonNode data) {
        String chatId = data.path("chatId").asText(null);
        String messageId = data.path("messageId").asText(null);
        JsonNode updates = data.get("updates");
        for (MessageUpdatedListener listener : messageUpdatedListeners) {
            safeInvoke(() -> listener.onMessageUpdated(chatId, messageId, updates));
        }
    }

    private void notifyMessageDeleted(JsonNode data) {
        String chatId = data.path("chatId").asText(null);
        String messageId = data.path("messageId").asText(null);
        for (MessageDeletedListener listener : messageDeletedListeners) {
            safeInvoke(() -> listener.onMessageDeleted(chatId, messageId));
        }
    }

    private void notifyReaction(JsonNode data) {
        String chatId = data.path("chatId").asText(null);
        String messageId = data.path("messageId").asText(null);
        String senderId = data.path("senderId").asText(null);
        String emoji = data.hasNonNull("emoji") && !data.path("emoji").asText().isEmpty() ? data.path("emoji").asText() : null;
        boolean removed = data.path("removed").asBoolean(false);
        for (ReactionListener listener : reactionListeners) {
            safeInvoke(() -> listener.onReactionReceived(chatId, messageId, senderId, emoji, removed));
        }
    }

    private void notifyGroupUpdated(JsonNode data) {
        String groupJid = data.path("groupJid").asText(null);
        JsonNode updates = data.get("updates");
        for (GroupUpdateListener listener : groupUpdateListeners) {
            safeInvoke(() -> listener.onGroupUpdated(groupJid, updates));
        }
    }

    private void notifyPresenceUpdated(JsonNode data) {
        String chatId = data.path("chatId").asText(null);
        JsonNode presences = data.get("presences");
        for (PresenceListener listener : presenceListeners) {
            safeInvoke(() -> listener.onPresenceUpdated(chatId, presences));
        }
    }

    private void notifyError(WhatsAppException error) {
        for (ErrorListener listener : errorListeners) {
            safeInvoke(() -> listener.onError(error));
        }
    }

    private void safeInvoke(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("listener de '{}' lancou uma excecao nao tratada: {}", instanceId, e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- helpers

    private BridgeConnection connection() {
        BridgeConnection current = this.connection;
        if (current == null || !current.isOpen()) {
            throw new ConnectionException(instanceId, BridgeErrorCode.NOT_CONNECTED,
                    "WhatsAppClient '" + instanceId + "' nao esta conectado ao bridge - chame connect() primeiro");
        }
        return current;
    }

    private Thread newReconnectThread(Runnable runnable) {
        Thread thread = new Thread(runnable, "angatu-whatsapp-reconnect-" + instanceId);
        thread.setDaemon(true);
        return thread;
    }

    private static String extractMessageId(JsonNode data) {
        return data.path("messageId").asText(null);
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private static Throwable unwrap(Throwable ex) {
        Throwable cause = ex;
        while ((cause instanceof java.util.concurrent.CompletionException || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    // ---------------------------------------------------------------- builder

    public static final class Builder {
        private String instanceId;
        private Path sessionDirectory;
        private String displayName;
        private Boolean markOnlineOnConnect;
        private Duration minReconnectDelay;
        private Duration maxReconnectDelay;
        private Integer maxReconnectAttempts;
        private PacingOptions pacing;
        private HumanizationOptions humanization;
        private String logLevel;
        private Duration reconnectCooldown;
        /** Distingue "nao configurado" de "configurado como null" (= desistir de vez). */
        private boolean reconnectCooldownSet;
        private String nodeVersion;
        private String bridgeHost;
        private Integer bridgePort;

        private Builder() {
        }

        public Builder instanceId(String instanceId) {
            this.instanceId = instanceId;
            return this;
        }

        public Builder sessionDirectory(String sessionDirectory) {
            this.sessionDirectory = Paths.get(Objects.requireNonNull(sessionDirectory, "sessionDirectory"));
            return this;
        }

        public Builder sessionDirectory(Path sessionDirectory) {
            this.sessionDirectory = sessionDirectory;
            return this;
        }

        /** Shown to WhatsApp as the linked device's name. Bridge default applies if unset. */
        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        /** Whether to mark this device as "online" as soon as the connection opens. Bridge default (false) applies if unset. */
        public Builder markOnlineOnConnect(boolean markOnlineOnConnect) {
            this.markOnlineOnConnect = markOnlineOnConnect;
            return this;
        }

        /** Shared by the bridge's own Baileys-level reconnect and this client's bridge-connection reconnect. Default 1s. */
        public Builder minReconnectDelay(Duration minReconnectDelay) {
            this.minReconnectDelay = minReconnectDelay;
            return this;
        }

        /** Default 30s. */
        public Builder maxReconnectDelay(Duration maxReconnectDelay) {
            this.maxReconnectDelay = maxReconnectDelay;
            return this;
        }

        /** How many consecutive bridge-connection reconnect attempts before giving up and moving to FAILED. Default 10. */
        /**
         * Ritmo com que as mensagens saem deste numero. Padrao:
         * {@link PacingOptions#padrao()} — 4 a 12 segundos entre envios, teto de
         * 10 por minuto.
         *
         * <p>A fila em si nao desliga: mesmo com {@link PacingOptions#desligado()}
         * os envios continuam saindo <b>um de cada vez</b>. Duas mensagens no
         * mesmo segundo pelo mesmo numero e o padrao que denuncia automacao.
         *
         * @param pacing ritmo desejado, ou {@code null} para o padrao
         * @return este builder
         */
        /**
         * Nivel de log do processo bridge: {@code trace}, {@code debug},
         * {@code info} (padrao), {@code warn}, {@code error} ou {@code fatal}.
         *
         * <p>Existia como variavel de ambiente ({@code ANGATU_BRIDGE_LOG_LEVEL})
         * mas era inalcancavel pela API — o valor era passado sempre como
         * {@code null} ao iniciar o processo. Em {@code debug} saem tambem as
         * repeticoes de ruido de protocolo que o nivel padrao resume.
         *
         * @param logLevel nivel do pino no bridge, ou {@code null} para o padrao
         * @return este builder
         */
        public Builder logLevel(String logLevel) {
            this.logLevel = logLevel;
            return this;
        }

        public Builder pacing(PacingOptions pacing) {
            this.pacing = pacing;
            return this;
        }

        /**
         * Sinais que a conta emite em volta de um envio: "digitando...",
         * "gravando audio...", online/offline e o azulzinho de leitura.
         *
         * <p>Padrao: {@link HumanizationOptions#padrao()} — digitando e gravando
         * ligados, leitura em {@link ReadReceiptMode#MANUAL}.
         *
         * @param humanization configuracao desejada, ou {@code null} para o padrao
         * @return este builder
         */
        public Builder humanization(HumanizationOptions humanization) {
            this.humanization = humanization;
            return this;
        }

        /**
         * Intervalo entre tentativas depois que o backoff exponencial se
         * esgotou. Padrao: 5 minutos.
         *
         * <p><b>O que isto conserta.</b> Ate esta versao, esgotados os
         * {@link #maxReconnectAttempts(int)} o cliente ia para
         * {@link ConnectionState#FAILED} e <b>ninguem mais tentava</b>. Era o
         * caminho pelo qual um numero "desconectava e nao voltava": a queda de
         * rede durava mais que as dez tentativas, o cliente desistia em
         * silencio e so um restart da aplicacao o traria de volta. Quem usava a
         * SDK tinha de escrever um vigia por fora so para isso.
         *
         * <p>Agora o estado {@code FAILED} continua sendo reportado — a
         * aplicacao precisa saber que o numero esta fora do ar —, mas as
         * tentativas <b>nao param</b>: elas passam a sair neste intervalo, para
         * sempre, e a conexao volta sozinha quando a rede voltar.
         *
         * <p>O intervalo e longo de proposito. Reconectar sem parar e pior do
         * que nao reconectar: cada tentativa reapresenta um cliente web ao
         * WhatsApp, e uma conta que faz isso vinte vezes por hora tem a
         * assinatura de uso automatizado que leva a suspensao.
         *
         * @param reconnectCooldown intervalo entre tentativas espacadas, ou
         *        {@code null} para o comportamento antigo (desistir de vez
         *        apos {@code maxReconnectAttempts})
         * @return este builder
         */
        public Builder reconnectCooldown(Duration reconnectCooldown) {
            this.reconnectCooldown = reconnectCooldown;
            this.reconnectCooldownSet = true;
            return this;
        }

        public Builder maxReconnectAttempts(int maxReconnectAttempts) {
            this.maxReconnectAttempts = maxReconnectAttempts;
            return this;
        }

        /** Node.js version to download if no compatible system installation is found. Defaults to {@code NodeRuntimeDownloader.DEFAULT_VERSION}. */
        public Builder nodeVersion(String nodeVersion) {
            this.nodeVersion = nodeVersion;
            return this;
        }

        /** Advanced: bind the local bridge WebSocket server to a non-default host. Never expose this beyond loopback without understanding the security implications - see README "Seguranca". */
        public Builder bridgeHost(String bridgeHost) {
            this.bridgeHost = bridgeHost;
            return this;
        }

        /** Advanced: use a fixed local port instead of an OS-assigned ephemeral one. */
        public Builder bridgePort(int bridgePort) {
            this.bridgePort = bridgePort;
            return this;
        }

        public WhatsAppClient build() {
            if (instanceId == null || instanceId.isBlank()) {
                throw new IllegalArgumentException("instanceId e obrigatorio");
            }
            if (sessionDirectory == null) {
                throw new IllegalArgumentException("sessionDirectory e obrigatorio");
            }
            return new WhatsAppClient(this);
        }
    }
}
