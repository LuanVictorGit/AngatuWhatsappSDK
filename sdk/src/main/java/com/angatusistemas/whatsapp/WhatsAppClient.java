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

    private final String instanceId;
    private final Path sessionDirectory;
    private final String displayName;
    private final Boolean markOnlineOnConnect;
    private final Duration minReconnectDelay;
    private final Duration maxReconnectDelay;
    private final int maxReconnectAttempts;
    private final String nodeVersion;
    private final String bridgeHost;
    private final Integer bridgePort;

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

    private WhatsAppClient(Builder builder) {
        this.instanceId = builder.instanceId;
        this.sessionDirectory = builder.sessionDirectory;
        this.displayName = builder.displayName;
        this.markOnlineOnConnect = builder.markOnlineOnConnect;
        this.minReconnectDelay = builder.minReconnectDelay != null ? builder.minReconnectDelay : DEFAULT_MIN_RECONNECT_DELAY;
        this.maxReconnectDelay = builder.maxReconnectDelay != null ? builder.maxReconnectDelay : DEFAULT_MAX_RECONNECT_DELAY;
        this.maxReconnectAttempts = builder.maxReconnectAttempts != null ? builder.maxReconnectAttempts : DEFAULT_MAX_RECONNECT_ATTEMPTS;
        this.nodeVersion = builder.nodeVersion;
        this.bridgeHost = builder.bridgeHost;
        this.bridgePort = builder.bridgePort;

        InstanceRegistry.register(instanceId, sessionDirectory);
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---------------------------------------------------------------- lifecycle

    public CompletableFuture<Void> connect() {
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("WhatsAppClient '" + instanceId + "' ja foi fechado (close())"));
        }
        userInitiatedStop = false;
        reconnectAttempts.set(0);
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
        } else if (currentConnection != null) {
            currentConnection.close();
        }

        if (currentProcess != null) {
            currentProcess.stop(SHUTDOWN_GRACE_PERIOD);
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

    public CompletableFuture<String> sendText(String to, String text) {
        return sendText(to, text, null);
    }

    public CompletableFuture<String> sendText(String to, String text, String quotedMessageId) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("to", Objects.requireNonNull(to, "to"));
        payload.put("text", Objects.requireNonNull(text, "text"));
        putIfPresent(payload, "quotedMessageId", quotedMessageId);
        return connection().sendRequest("send_text", instanceId, payload).thenApply(WhatsAppClient::extractMessageId);
    }

    public CompletableFuture<String> sendImage(SendImageRequest request) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("to", request.to());
        payload.put("filePath", request.filePath());
        putIfPresent(payload, "caption", request.caption());
        putIfPresent(payload, "quotedMessageId", request.quotedMessageId());
        return connection().sendRequest("send_image", instanceId, payload).thenApply(WhatsAppClient::extractMessageId);
    }

    public CompletableFuture<String> sendVideo(SendVideoRequest request) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("to", request.to());
        payload.put("filePath", request.filePath());
        putIfPresent(payload, "caption", request.caption());
        payload.put("gifPlayback", request.gifPlayback());
        putIfPresent(payload, "quotedMessageId", request.quotedMessageId());
        return connection().sendRequest("send_video", instanceId, payload).thenApply(WhatsAppClient::extractMessageId);
    }

    public CompletableFuture<String> sendAudio(SendAudioRequest request) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("to", request.to());
        payload.put("filePath", request.filePath());
        payload.put("ptt", request.ptt());
        putIfPresent(payload, "quotedMessageId", request.quotedMessageId());
        return connection().sendRequest("send_audio", instanceId, payload).thenApply(WhatsAppClient::extractMessageId);
    }

    public CompletableFuture<String> sendDocument(SendDocumentRequest request) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("to", request.to());
        payload.put("filePath", request.filePath());
        payload.put("fileName", request.fileName());
        payload.put("mimetype", request.mimetype());
        putIfPresent(payload, "caption", request.caption());
        putIfPresent(payload, "quotedMessageId", request.quotedMessageId());
        return connection().sendRequest("send_document", instanceId, payload).thenApply(WhatsAppClient::extractMessageId);
    }

    public CompletableFuture<String> sendSticker(SendStickerRequest request) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("to", request.to());
        payload.put("filePath", request.filePath());
        putIfPresent(payload, "quotedMessageId", request.quotedMessageId());
        return connection().sendRequest("send_sticker", instanceId, payload).thenApply(WhatsAppClient::extractMessageId);
    }

    public CompletableFuture<String> sendLocation(SendLocationRequest request) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("to", request.to());
        payload.put("latitude", request.latitude());
        payload.put("longitude", request.longitude());
        putIfPresent(payload, "name", request.name());
        putIfPresent(payload, "address", request.address());
        putIfPresent(payload, "quotedMessageId", request.quotedMessageId());
        return connection().sendRequest("send_location", instanceId, payload).thenApply(WhatsAppClient::extractMessageId);
    }

    public CompletableFuture<String> sendContact(SendContactRequest request) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("to", request.to());
        payload.put("displayName", request.displayName());
        payload.put("phoneNumber", request.phoneNumber());
        putIfPresent(payload, "organization", request.organization());
        putIfPresent(payload, "quotedMessageId", request.quotedMessageId());
        return connection().sendRequest("send_contact", instanceId, payload).thenApply(WhatsAppClient::extractMessageId);
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
        return connection().sendRequest("edit_message", instanceId, payload).thenApply(WhatsAppClient::extractMessageId);
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

    public CompletableFuture<Contact> getContact(String jid) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("jid", Objects.requireNonNull(jid, "jid"));
        return connection().sendRequest("get_contact", instanceId, payload).thenApply(Contact::fromJson);
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

    private CompletableFuture<Void> startBridgeAndConnectInstance() {
        BridgeProcessManager newProcessManager;
        try {
            newProcessManager = BridgeProcessManager.start(
                    new BridgeProcessManager.StartOptions(bridgeHost, bridgePort, null, nodeVersion));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
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
        this.connection = newConnection;
        return newConnection.connect(HANDSHAKE_TIMEOUT).thenApply(ack -> {
            newConnection.whenClosed().thenAccept(this::handleConnectionClosed);
            return new ProtocolAck();
        });
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

    private void handleConnectionClosed(DisconnectInfo info) {
        if (userInitiatedStop || closed.get()) {
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
        if (attempt > maxReconnectAttempts) {
            notifyConnectionState(ConnectionState.FAILED, DisconnectCause.UNKNOWN,
                    "numero maximo de tentativas de reconexao excedido (" + maxReconnectAttempts + ")");
            notifyError(new ConnectionException(instanceId, BridgeErrorCode.CONNECTION_FAILED,
                    "desistindo de reconectar ao bridge para '" + instanceId + "' apos " + maxReconnectAttempts + " tentativas"));
            return;
        }

        long boundedExponent = Math.min(attempt - 1, 20);
        long delayMs = Math.min(maxReconnectDelay.toMillis(), minReconnectDelay.toMillis() * (1L << boundedExponent))
                + ThreadLocalRandom.current().nextLong(250);
        log.info("agendando reconexao ao bridge para '{}' em {} ms (tentativa {}/{})", instanceId, delayMs, attempt, maxReconnectAttempts);
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

    /** The bridge process survived; only the Java<->bridge WebSocket needs to be re-established, not the WhatsApp connection itself. */
    private CompletableFuture<Void> reconnectWebSocketOnly() {
        BridgeProcessManager currentProcess = this.processManager;
        int port;
        try {
            port = currentProcess.portReady().getNow(-1);
        } catch (RuntimeException e) {
            port = -1;
        }
        if (port <= 0) {
            return startBridgeAndConnectInstance();
        }

        BridgeConnection newConnection = new BridgeConnection(URI.create("ws://127.0.0.1:" + port), currentProcess.authToken());
        newConnection.setEventListener(this::handleBridgeEvent);
        this.connection = newConnection;
        return newConnection.connect(HANDSHAKE_TIMEOUT)
                .thenCompose(ack -> {
                    newConnection.whenClosed().thenAccept(this::handleConnectionClosed);
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
        for (MessageListener listener : messageListeners) {
            safeInvoke(() -> listener.onMessageReceived(message));
        }
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
