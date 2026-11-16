package com.angatusistemas.whatsapp;

import br.com.angatusistemas.lib.console.Console;
import com.angatusistemas.whatsapp.bridge.BridgeHost;
import com.angatusistemas.whatsapp.bridge.OrphanBridges;
import com.angatusistemas.whatsapp.exception.BridgeErrorCode;
import com.angatusistemas.whatsapp.exception.ConnectionException;
import com.angatusistemas.whatsapp.exception.QueueException;
import com.angatusistemas.whatsapp.exception.WhatsAppException;
import com.angatusistemas.whatsapp.internal.Decode;
import com.angatusistemas.whatsapp.internal.EventInbox;
import com.angatusistemas.whatsapp.internal.InstanceRegistry;
import com.angatusistemas.whatsapp.internal.Jids;
import com.angatusistemas.whatsapp.internal.Json;
import com.angatusistemas.whatsapp.internal.QrCodeRenderer;
import com.angatusistemas.whatsapp.internal.SessionInspector;
import com.angatusistemas.whatsapp.internal.Threads;
import com.angatusistemas.whatsapp.listener.ButtonListener;
import com.angatusistemas.whatsapp.listener.CallListener;
import com.angatusistemas.whatsapp.listener.ConnectionStateListener;
import com.angatusistemas.whatsapp.listener.ErrorListener;
import com.angatusistemas.whatsapp.listener.GroupUpdateListener;
import com.angatusistemas.whatsapp.listener.MessageDeletedListener;
import com.angatusistemas.whatsapp.listener.MessageListener;
import com.angatusistemas.whatsapp.listener.MessageUpdatedListener;
import com.angatusistemas.whatsapp.listener.PollVoteListener;
import com.angatusistemas.whatsapp.listener.PresenceListener;
import com.angatusistemas.whatsapp.listener.QrCodeListener;
import com.angatusistemas.whatsapp.listener.ReactionListener;
import com.angatusistemas.whatsapp.model.Account;
import com.angatusistemas.whatsapp.model.BridgeStats;
import com.angatusistemas.whatsapp.model.BusinessProfile;
import com.angatusistemas.whatsapp.model.ButtonClick;
import com.angatusistemas.whatsapp.model.ButtonReply;
import com.angatusistemas.whatsapp.model.ButtonStyle;
import com.angatusistemas.whatsapp.model.Contact;
import com.angatusistemas.whatsapp.model.ContactStatus;
import com.angatusistemas.whatsapp.model.Group;
import com.angatusistemas.whatsapp.model.GroupCheck;
import com.angatusistemas.whatsapp.model.IncomingCall;
import com.angatusistemas.whatsapp.model.MediaFile;
import com.angatusistemas.whatsapp.model.Message;
import com.angatusistemas.whatsapp.model.NumberCheck;
import com.angatusistemas.whatsapp.model.ParticipantAction;
import com.angatusistemas.whatsapp.model.ParticipantResult;
import com.angatusistemas.whatsapp.model.PendingInteraction;
import com.angatusistemas.whatsapp.model.Presence;
import com.angatusistemas.whatsapp.model.PrivacySetting;
import com.angatusistemas.whatsapp.model.QrCode;
import com.angatusistemas.whatsapp.model.SessionStatus;
import com.angatusistemas.whatsapp.options.BridgeOptions;
import com.angatusistemas.whatsapp.options.CallOptions;
import com.angatusistemas.whatsapp.options.HumanizationOptions;
import com.angatusistemas.whatsapp.options.PacingOptions;
import com.angatusistemas.whatsapp.options.ReadReceiptMode;
import com.angatusistemas.whatsapp.queue.ButtonMenus;
import com.angatusistemas.whatsapp.queue.Interaction;
import com.angatusistemas.whatsapp.queue.InteractionQueue;
import com.angatusistemas.whatsapp.queue.Kind;
import com.angatusistemas.whatsapp.request.MessageRef;
import com.angatusistemas.whatsapp.request.SendAudioRequest;
import com.angatusistemas.whatsapp.request.SendButtonsRequest;
import com.angatusistemas.whatsapp.request.SendContactRequest;
import com.angatusistemas.whatsapp.request.SendDocumentRequest;
import com.angatusistemas.whatsapp.request.SendImageRequest;
import com.angatusistemas.whatsapp.request.SendLocationRequest;
import com.angatusistemas.whatsapp.request.SendStickerRequest;
import com.angatusistemas.whatsapp.request.SendTextRequest;
import com.angatusistemas.whatsapp.request.SendVideoRequest;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * One WhatsApp account. Every operation that reaches WhatsApp is queued (InteractionQueue) and
 * leaves at least 500 ms after the previous one, with the human signals around it; lifecycle calls
 * (connect, pairing, logout) go straight to the bridge. All clients of a JVM share one bridge
 * process. Only messages that arrive after the client starts are delivered; history is never
 * requested. Calls return futures and never block the caller; listeners run on one virtual
 * thread per client, in order.
 *
 * @author Angatu Sistemas
 */
public final class WhatsAppClient implements AutoCloseable {

    private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration PAIRING_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);
    private static final long KEEP_ALIVE_MIN_MS = 4 * 60_000;
    private static final long KEEP_ALIVE_JITTER_MS = 2 * 60_000;

    private final String instanceId;
    private final Path sessionDirectory;
    private final String deviceName;
    private final boolean includeRawData;
    private final int maxLoginCycles;
    private final Duration minReconnectDelay;
    private final Duration maxReconnectDelay;
    private final BridgeOptions bridgeOptions;
    private final long acceptSince;
    private final BridgeHost host = BridgeHost.shared();
    private final InteractionQueue queue;
    private final EventInbox inbox;
    private final Attachment attachment = new Attachment();
    private final AtomicReference<ConnectionState> state = new AtomicReference<>(ConnectionState.DISCONNECTED);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final List<MessageListener> messageListeners = new CopyOnWriteArrayList<>();
    private final List<MessageUpdatedListener> messageUpdatedListeners = new CopyOnWriteArrayList<>();
    private final List<MessageDeletedListener> messageDeletedListeners = new CopyOnWriteArrayList<>();
    private final List<ReactionListener> reactionListeners = new CopyOnWriteArrayList<>();
    private final List<ConnectionStateListener> connectionStateListeners = new CopyOnWriteArrayList<>();
    private final List<QrCodeListener> qrCodeListeners = new CopyOnWriteArrayList<>();
    private final List<GroupUpdateListener> groupUpdateListeners = new CopyOnWriteArrayList<>();
    private final List<PresenceListener> presenceListeners = new CopyOnWriteArrayList<>();
    private final List<CallListener> callListeners = new CopyOnWriteArrayList<>();
    private final List<ButtonListener> buttonListeners = new CopyOnWriteArrayList<>();
    private final List<PollVoteListener> pollVoteListeners = new CopyOnWriteArrayList<>();
    private final List<ErrorListener> errorListeners = new CopyOnWriteArrayList<>();
    private volatile PacingOptions pacing;
    private volatile HumanizationOptions humanization;
    private volatile CallOptions callOptions;
    private volatile QrCode currentQr;
    private volatile boolean wantConnected;
    private volatile ScheduledFuture<?> keepAlive;

    private WhatsAppClient(Builder builder) {
        this.instanceId = builder.instanceId;
        this.sessionDirectory = builder.sessionDirectory.toAbsolutePath().normalize();
        this.deviceName = builder.deviceName;
        this.includeRawData = builder.includeRawData;
        this.maxLoginCycles = builder.maxLoginCycles;
        this.minReconnectDelay = builder.minReconnectDelay;
        this.maxReconnectDelay = builder.maxReconnectDelay;
        this.bridgeOptions = builder.bridgeOptions;
        this.pacing = builder.pacing;
        this.humanization = builder.humanization;
        this.callOptions = builder.callOptions;
        this.acceptSince = Instant.now().getEpochSecond();
        InstanceRegistry.register(instanceId, sessionDirectory);
        this.queue = new InteractionQueue(instanceId, new Gateway(), () -> pacing, () -> humanization);
        this.inbox = new EventInbox(instanceId);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static SessionStatus inspectSession(Path sessionDirectory) {
        return SessionInspector.inspect(sessionDirectory);
    }

    // ------------------------------------------------------------------ lifecycle

    public CompletableFuture<Void> connect() {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new QueueException(instanceId, BridgeErrorCode.QUEUE_CLOSED, "client is closed"));
        }
        wantConnected = true;
        queue.start();
        host.attach(instanceId, attachment, bridgeOptions);
        return host.ensureRunning()
                .thenComposeAsync(ignored -> createInstance(), Threads.VIRTUAL)
                .thenCompose(ignored -> control("connect", new JsonObject(), CONTROL_TIMEOUT))
                .thenApply(ignored -> (Void) null);
    }

    public CompletableFuture<String> requestPairingCode(String phoneNumber) {
        return requestPairingCode(phoneNumber, null);
    }

    public CompletableFuture<String> requestPairingCode(String phoneNumber, String customCode) {
        JsonObject payload = new JsonObject();
        payload.addProperty("phoneNumber", Jids.digits(Objects.requireNonNull(phoneNumber, "phoneNumber")));
        Json.put(payload, "customCode", customCode);
        return connect().thenCompose(ignored -> control("request_pairing_code", payload, PAIRING_TIMEOUT))
                .thenApply(data -> Json.str(data, "code"));
    }

    public CompletableFuture<Void> disconnect() {
        wantConnected = false;
        stopKeepAlive();
        if (!host.isOpen()) {
            return CompletableFuture.completedFuture(null);
        }
        return control("disconnect", new JsonObject(), CONTROL_TIMEOUT).thenApply(ignored -> null);
    }

    public CompletableFuture<Void> logout() {
        wantConnected = false;
        stopKeepAlive();
        if (!host.isOpen()) {
            return CompletableFuture.failedFuture(new ConnectionException(instanceId, BridgeErrorCode.NOT_CONNECTED,
                    "cannot log out: the client is not connected"));
        }
        return control("logout", new JsonObject(), CONTROL_TIMEOUT).thenApply(ignored -> null);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        wantConnected = false;
        stopKeepAlive();
        queue.close();
        if (host.isOpen()) {
            try {
                control("destroy_instance", new JsonObject(), CLOSE_TIMEOUT).get(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | java.util.concurrent.TimeoutException e) {
                Console.debug("[whatsapp] %s: destroy_instance did not finish: %s", instanceId, e.getMessage());
            }
        }
        host.detach(instanceId);
        inbox.close();
        InstanceRegistry.unregister(instanceId);
    }

    public String getInstanceId() {
        return instanceId;
    }

    public ConnectionState getConnectionState() {
        return state.get();
    }

    public boolean isConnected() {
        return state.get() == ConnectionState.CONNECTED && host.isOpen();
    }

    public SessionStatus sessionStatus() {
        return SessionInspector.inspect(sessionDirectory);
    }

    public Optional<QrCode> getQrCode() {
        return Optional.ofNullable(currentQr);
    }

    public String getCurrentQrCode() {
        QrCode qr = currentQr;
        return qr == null ? null : qr.value();
    }

    public byte[] getQrCodePng() {
        return getQrCodePng(300);
    }

    public byte[] getQrCodePng(int sizePixels) {
        QrCode qr = currentQr;
        return qr == null ? null : QrCodeRenderer.png(qr.value(), sizePixels);
    }

    public String getQrCodeText() {
        QrCode qr = currentQr;
        return qr == null ? null : QrCodeRenderer.text(qr.value());
    }

    public CompletableFuture<BridgeStats> bridgeStats() {
        return host.request("bridge_stats", null, new JsonObject(), CONTROL_TIMEOUT).thenApply(Decode::stats);
    }

    // ------------------------------------------------------------------ listeners

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

    public void addCallListener(CallListener listener) {
        callListeners.add(Objects.requireNonNull(listener));
    }

    public void addButtonListener(ButtonListener listener) {
        buttonListeners.add(Objects.requireNonNull(listener));
    }

    public void addPollVoteListener(PollVoteListener listener) {
        pollVoteListeners.add(Objects.requireNonNull(listener));
    }

    public void addErrorListener(ErrorListener listener) {
        errorListeners.add(Objects.requireNonNull(listener));
    }

    // ------------------------------------------------------------------ messages

    public CompletableFuture<String> sendText(String to, String text) {
        return sendText(SendTextRequest.of(to, text));
    }

    public CompletableFuture<String> sendText(SendTextRequest request) {
        String chat = Jids.of(request.to());
        JsonObject payload = target(chat, request.quotedMessageId());
        payload.addProperty("text", request.text());
        return messageId(queue.submit(Interaction.send("send_text", payload, chat, request.text().length(), request.typing(), Presence.COMPOSING)));
    }

    public CompletableFuture<String> sendImage(SendImageRequest request) {
        String chat = Jids.of(request.to());
        JsonObject payload = target(chat, request.quotedMessageId());
        payload.addProperty("filePath", existing(request.file()));
        Json.put(payload, "caption", request.caption());
        return messageId(queue.submit(Interaction.send("send_image", payload, chat, length(request.caption()), null, Presence.COMPOSING)));
    }

    public CompletableFuture<String> sendVideo(SendVideoRequest request) {
        String chat = Jids.of(request.to());
        JsonObject payload = target(chat, request.quotedMessageId());
        payload.addProperty("filePath", existing(request.file()));
        Json.put(payload, "caption", request.caption());
        payload.addProperty("gifPlayback", request.gifPlayback());
        return messageId(queue.submit(Interaction.send("send_video", payload, chat, length(request.caption()), null, Presence.COMPOSING)));
    }

    public CompletableFuture<String> sendAudio(SendAudioRequest request) {
        String chat = Jids.of(request.to());
        JsonObject payload = target(chat, request.quotedMessageId());
        payload.addProperty("filePath", existing(request.file()));
        payload.addProperty("ptt", request.voiceNote());
        Presence presence = request.voiceNote() ? Presence.RECORDING : Presence.COMPOSING;
        return messageId(queue.submit(Interaction.send("send_audio", payload, chat, 0, null, presence)));
    }

    public CompletableFuture<String> sendDocument(SendDocumentRequest request) {
        String chat = Jids.of(request.to());
        JsonObject payload = target(chat, request.quotedMessageId());
        payload.addProperty("filePath", existing(request.file()));
        payload.addProperty("fileName", request.fileName());
        payload.addProperty("mimetype", request.mimetype());
        Json.put(payload, "caption", request.caption());
        return messageId(queue.submit(Interaction.send("send_document", payload, chat, length(request.caption()), null, Presence.COMPOSING)));
    }

    public CompletableFuture<String> sendSticker(SendStickerRequest request) {
        String chat = Jids.of(request.to());
        JsonObject payload = target(chat, request.quotedMessageId());
        payload.addProperty("filePath", existing(request.file()));
        return messageId(queue.submit(Interaction.send("send_sticker", payload, chat, 0, Duration.ZERO, null)));
    }

    public CompletableFuture<String> sendLocation(SendLocationRequest request) {
        String chat = Jids.of(request.to());
        JsonObject payload = target(chat, request.quotedMessageId());
        payload.addProperty("latitude", request.latitude());
        payload.addProperty("longitude", request.longitude());
        Json.put(payload, "name", request.name());
        Json.put(payload, "address", request.address());
        return messageId(queue.submit(Interaction.send("send_location", payload, chat, 0, null, Presence.COMPOSING)));
    }

    public CompletableFuture<String> sendContact(SendContactRequest request) {
        String chat = Jids.of(request.to());
        JsonObject payload = target(chat, request.quotedMessageId());
        payload.addProperty("displayName", request.displayName());
        payload.addProperty("phoneNumber", request.phoneNumber());
        Json.put(payload, "organization", request.organization());
        return messageId(queue.submit(Interaction.send("send_contact", payload, chat, 0, null, Presence.COMPOSING)));
    }

    public CompletableFuture<String> sendButtons(SendButtonsRequest request) {
        String chat = Jids.of(request.to());
        if (request.style() == ButtonStyle.TEXT) {
            String menu = ButtonMenus.render(request.title(), request.text(), request.footer(), request.buttons());
            return sendText(new SendTextRequest(chat, menu, request.quotedMessageId(), null)).thenApply(id -> {
                ButtonMenus.remember(instanceId, chat, id, request.buttons());
                return id;
            });
        }
        JsonObject payload = target(chat, request.quotedMessageId());
        payload.addProperty("text", request.text());
        Json.put(payload, "title", request.title());
        Json.put(payload, "footer", request.footer());
        JsonArray buttons = new JsonArray(request.buttons().size());
        request.buttons().forEach(button -> {
            JsonObject item = new JsonObject();
            item.addProperty("id", button.id());
            item.addProperty("text", button.label());
            buttons.add(item);
        });
        payload.add("buttons", buttons);
        return messageId(queue.submit(Interaction.send("send_buttons", payload, chat, request.text().length(), null, Presence.COMPOSING)));
    }

    public CompletableFuture<String> sendPoll(String to, String question, List<String> options, int selectableCount) {
        String chat = Jids.of(to);
        JsonObject payload = target(chat, null);
        payload.addProperty("name", Objects.requireNonNull(question, "question"));
        payload.add("options", Json.stringArray(Objects.requireNonNull(options, "options")));
        payload.addProperty("selectableCount", selectableCount);
        return messageId(queue.submit(Interaction.send("send_poll", payload, chat, question.length(), null, Presence.COMPOSING)));
    }

    public CompletableFuture<String> forwardMessage(MessageRef message, String to) {
        String chat = Jids.of(to);
        JsonObject payload = new JsonObject();
        payload.addProperty("chatId", message.chatId());
        payload.addProperty("messageId", message.messageId());
        payload.addProperty("to", chat);
        return messageId(queue.submit(Interaction.send("forward_message", payload, chat, 0, null, Presence.COMPOSING)));
    }

    public CompletableFuture<Void> sendReaction(MessageRef message, String emoji) {
        JsonObject payload = ref(message);
        payload.addProperty("emoji", Objects.requireNonNull(emoji, "emoji"));
        return done(queue.submit(Interaction.of("send_reaction", Kind.ACTION, payload, message.chatId())));
    }

    public CompletableFuture<Void> removeReaction(MessageRef message) {
        return sendReaction(message, "");
    }

    public CompletableFuture<String> editMessage(MessageRef message, String text) {
        JsonObject payload = ref(message);
        payload.addProperty("text", Objects.requireNonNull(text, "text"));
        return messageId(queue.submit(Interaction.send("edit_message", payload, message.chatId(), text.length(), null, Presence.COMPOSING)));
    }

    public CompletableFuture<Void> deleteMessage(MessageRef message) {
        return done(queue.submit(Interaction.of("delete_message", Kind.ACTION, ref(message), message.chatId())));
    }

    public CompletableFuture<Void> starMessage(MessageRef message, boolean star) {
        JsonObject payload = ref(message);
        payload.addProperty("star", star);
        return done(queue.submit(Interaction.of("star_message", Kind.ACTION, payload, message.chatId())));
    }

    // ------------------------------------------------------------------ presence and receipts

    public CompletableFuture<Void> sendTyping(String chatId, Duration duration) {
        String chat = Jids.of(chatId);
        CompletableFuture<Void> typing = sendPresence(chat, Presence.COMPOSING);
        JsonObject paused = new JsonObject();
        paused.addProperty("chatId", chat);
        paused.addProperty("presence", Presence.PAUSED.wireValue());
        queue.submit(Interaction.of("send_presence", Kind.PRESENCE, paused, chat).after(duration).replacing("paused:" + chat));
        return typing;
    }

    public CompletableFuture<Void> sendPresence(String chatId, Presence presence) {
        JsonObject payload = new JsonObject();
        if (chatId != null) {
            payload.addProperty("chatId", Jids.of(chatId));
        }
        payload.addProperty("presence", Objects.requireNonNull(presence, "presence").wireValue());
        return done(queue.submit(Interaction.of("send_presence", Kind.PRESENCE, payload, chatId)));
    }

    public CompletableFuture<Void> setOnline(boolean online) {
        return sendPresence(null, online ? Presence.AVAILABLE : Presence.UNAVAILABLE);
    }

    public CompletableFuture<Void> subscribePresence(String chatId) {
        String chat = Jids.of(chatId);
        JsonObject payload = new JsonObject();
        payload.addProperty("chatId", chat);
        return done(queue.submit(Interaction.of("subscribe_presence", Kind.QUERY, payload, chat).replacing("subscribe:" + chat)));
    }

    public CompletableFuture<Void> markRead(Message message) {
        return markRead(message.chatId(), message.messageId(), message.participant());
    }

    public CompletableFuture<Void> markRead(String chatId, String messageId) {
        return markRead(chatId, messageId, null);
    }

    public CompletableFuture<Void> markChatRead(String chatId) {
        return markRead(chatId, null, null);
    }

    private CompletableFuture<Void> markRead(String chatId, String messageId, String participant) {
        String chat = Jids.of(chatId);
        JsonObject payload = new JsonObject();
        payload.addProperty("chatId", chat);
        Json.put(payload, "messageId", messageId);
        Json.put(payload, "participant", participant);
        return done(queue.submit(Interaction.of("mark_read", Kind.RECEIPT, payload, chat)));
    }

    public CompletableFuture<Void> rejectCall(IncomingCall call) {
        JsonObject payload = new JsonObject();
        payload.addProperty("callId", call.callId());
        payload.addProperty("from", call.from());
        return done(queue.submit(Interaction.of("reject_call", Kind.CALL, payload, call.chatId())));
    }

    // ------------------------------------------------------------------ media

    public CompletableFuture<MediaFile> downloadMedia(Message message, Path target) {
        return downloadMedia(message.chatId(), message.messageId(), target);
    }

    public CompletableFuture<MediaFile> downloadMedia(String chatId, String messageId, Path target) {
        Path destination = target.toAbsolutePath().normalize();
        JsonObject payload = new JsonObject();
        payload.addProperty("chatId", Jids.of(chatId));
        payload.addProperty("messageId", Objects.requireNonNull(messageId, "messageId"));
        payload.addProperty("targetPath", destination.toString());
        return queue.submit(Interaction.of("download_media", Kind.QUERY, payload, chatId).withTimeout(Duration.ofMinutes(5)))
                .thenApply(data -> new MediaFile(Path.of(Json.str(data, "path")), Json.str(data, "mimetype"), Json.number(data, "size", 0)));
    }

    // ------------------------------------------------------------------ contacts

    public CompletableFuture<NumberCheck> checkNumber(String number) {
        JsonObject payload = new JsonObject();
        payload.addProperty("number", Jids.digits(Objects.requireNonNull(number, "number")));
        return queue.submit(Interaction.of("check_number", Kind.QUERY, payload, null)).thenApply(Decode::numberCheck);
    }

    public CompletableFuture<List<NumberCheck>> checkNumbers(Collection<String> numbers) {
        JsonObject payload = new JsonObject();
        payload.add("numbers", Json.stringArray(numbers.stream().map(Jids::digits).toList()));
        return queue.submit(Interaction.of("check_numbers", Kind.QUERY, payload, null)).thenApply(Decode::numberChecks);
    }

    public CompletableFuture<Boolean> contactExists(String number) {
        return checkNumber(number).thenApply(NumberCheck::exists);
    }

    public CompletableFuture<Contact> getContact(String jid) {
        return query("get_contact", "jid", Jids.of(jid)).thenApply(Decode::contact);
    }

    public CompletableFuture<Optional<String>> getProfilePicture(String jid) {
        return query("get_profile_picture", "jid", Jids.of(jid)).thenApply(data -> Optional.ofNullable(Json.str(data, "profilePictureUrl")));
    }

    public CompletableFuture<ContactStatus> getContactStatus(String jid) {
        return query("get_contact_status", "jid", Jids.of(jid)).thenApply(Decode::contactStatus);
    }

    public CompletableFuture<Optional<BusinessProfile>> getBusinessProfile(String jid) {
        return query("get_business_profile", "jid", Jids.of(jid))
                .thenApply(data -> Optional.ofNullable(Json.object(data, "profile")).map(Decode::businessProfile));
    }

    public CompletableFuture<Void> blockContact(String jid) {
        return setBlocked(jid, true);
    }

    public CompletableFuture<Void> unblockContact(String jid) {
        return setBlocked(jid, false);
    }

    public CompletableFuture<List<String>> getBlocklist() {
        return queue.submit(Interaction.of("get_blocklist", Kind.QUERY, new JsonObject(), null)).thenApply(data -> List.copyOf(Json.strings(data, "jids")));
    }

    private CompletableFuture<Void> setBlocked(String jid, boolean blocked) {
        JsonObject payload = new JsonObject();
        payload.addProperty("jid", Jids.of(jid));
        payload.addProperty("blocked", blocked);
        return done(queue.submit(Interaction.of("set_blocked", Kind.ACTION, payload, null)));
    }

    // ------------------------------------------------------------------ own account

    public CompletableFuture<Account> getMe() {
        return queue.submit(Interaction.of("get_me", Kind.ACTION, new JsonObject(), null)).thenApply(Decode::account);
    }

    public CompletableFuture<Void> updateProfileName(String name) {
        return action("update_profile_name", "name", Objects.requireNonNull(name, "name"));
    }

    public CompletableFuture<Void> updateProfileStatus(String status) {
        return action("update_profile_status", "status", Objects.requireNonNull(status, "status"));
    }

    public CompletableFuture<Void> updateProfilePicture(Path image) {
        return action("update_profile_picture", "filePath", existing(image));
    }

    public CompletableFuture<Void> removeProfilePicture() {
        return done(queue.submit(Interaction.of("remove_profile_picture", Kind.ACTION, new JsonObject(), null)));
    }

    public CompletableFuture<Map<String, String>> getPrivacySettings() {
        return queue.submit(Interaction.of("get_privacy_settings", Kind.QUERY, new JsonObject(), null)).thenApply(data -> {
            JsonObject settings = Json.object(data, "settings");
            Map<String, String> values = new HashMap<>();
            if (settings != null) {
                for (Map.Entry<String, JsonElement> entry : settings.entrySet()) {
                    values.put(entry.getKey(), entry.getValue().isJsonPrimitive() ? entry.getValue().getAsString() : entry.getValue().toString());
                }
            }
            return Map.copyOf(values);
        });
    }

    public CompletableFuture<Void> updatePrivacy(PrivacySetting setting, String value) {
        if (!setting.accepted().contains(value)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(setting + " accepts " + setting.accepted()));
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("setting", setting.wireValue());
        payload.addProperty("value", value);
        return done(queue.submit(Interaction.of("update_privacy", Kind.ACTION, payload, null)));
    }

    // ------------------------------------------------------------------ chats

    public CompletableFuture<Void> muteChat(String chatId, Duration duration) {
        JsonObject payload = chat(chatId, "mute");
        if (duration != null) {
            payload.addProperty("muteUntilMs", System.currentTimeMillis() + duration.toMillis());
        }
        return modifyChat(payload);
    }

    public CompletableFuture<Void> unmuteChat(String chatId) {
        return modifyChat(chat(chatId, "unmute"));
    }

    public CompletableFuture<Void> pinChat(String chatId, boolean pin) {
        return modifyChat(chat(chatId, pin ? "pin" : "unpin"));
    }

    public CompletableFuture<Void> archiveChat(String chatId, boolean archive) {
        return modifyChat(chat(chatId, archive ? "archive" : "unarchive"));
    }

    public CompletableFuture<Void> markChatUnread(String chatId) {
        return modifyChat(chat(chatId, "mark_unread"));
    }

    public CompletableFuture<Void> setDisappearingMessages(String chatId, Duration expiration) {
        JsonObject payload = new JsonObject();
        payload.addProperty("chatId", Jids.of(chatId));
        payload.addProperty("seconds", expiration == null ? 0 : expiration.toSeconds());
        return done(queue.submit(Interaction.of("set_disappearing", Kind.ACTION, payload, chatId)));
    }

    private CompletableFuture<Void> modifyChat(JsonObject payload) {
        return done(queue.submit(Interaction.of("modify_chat", Kind.ACTION, payload, Json.str(payload, "chatId"))));
    }

    // ------------------------------------------------------------------ groups

    public CompletableFuture<Group> getGroupMetadata(String groupJid) {
        return query("get_group_metadata", "groupJid", groupJid).thenApply(Decode::group);
    }

    public CompletableFuture<GroupCheck> groupExists(String groupJid) {
        return query("group_exists", "groupJid", groupJid).thenApply(Decode::groupCheck);
    }

    public CompletableFuture<List<Group>> listGroups() {
        return queue.submit(Interaction.of("list_groups", Kind.QUERY, new JsonObject(), null)).thenApply(data -> {
            List<Group> groups = new ArrayList<>();
            for (JsonElement element : Json.array(data, "groups")) {
                if (element.isJsonObject()) {
                    groups.add(Decode.group(element.getAsJsonObject()));
                }
            }
            return List.copyOf(groups);
        });
    }

    public CompletableFuture<Group> createGroup(String subject, List<String> participants) {
        JsonObject payload = new JsonObject();
        payload.addProperty("subject", Objects.requireNonNull(subject, "subject"));
        payload.add("participants", Json.stringArray(participants.stream().map(Jids::of).toList()));
        return queue.submit(Interaction.of("create_group", Kind.ACTION, payload, null)).thenApply(Decode::group);
    }

    public CompletableFuture<List<ParticipantResult>> updateGroupParticipants(String groupJid, List<String> participants, ParticipantAction action) {
        JsonObject payload = new JsonObject();
        payload.addProperty("groupJid", groupJid);
        payload.add("participants", Json.stringArray(participants.stream().map(Jids::of).toList()));
        payload.addProperty("action", action.wireValue());
        return queue.submit(Interaction.of("update_group_participants", Kind.ACTION, payload, groupJid)).thenApply(Decode::participantResults);
    }

    public CompletableFuture<Void> updateGroupSubject(String groupJid, String subject) {
        JsonObject payload = group(groupJid);
        payload.addProperty("subject", Objects.requireNonNull(subject, "subject"));
        return done(queue.submit(Interaction.of("update_group_subject", Kind.ACTION, payload, groupJid)));
    }

    public CompletableFuture<Void> updateGroupDescription(String groupJid, String description) {
        JsonObject payload = group(groupJid);
        payload.addProperty("description", Objects.requireNonNull(description, "description"));
        return done(queue.submit(Interaction.of("update_group_description", Kind.ACTION, payload, groupJid)));
    }

    public CompletableFuture<Void> updateGroupPicture(String groupJid, Path image) {
        JsonObject payload = new JsonObject();
        payload.addProperty("jid", groupJid);
        payload.addProperty("filePath", existing(image));
        return done(queue.submit(Interaction.of("update_profile_picture", Kind.ACTION, payload, groupJid)));
    }

    public CompletableFuture<Void> setGroupAnnounceOnly(String groupJid, boolean announceOnly) {
        return groupSetting(groupJid, announceOnly ? "announcement" : "not_announcement");
    }

    public CompletableFuture<Void> setGroupLocked(String groupJid, boolean locked) {
        return groupSetting(groupJid, locked ? "locked" : "unlocked");
    }

    public CompletableFuture<Void> leaveGroup(String groupJid) {
        return done(queue.submit(Interaction.of("leave_group", Kind.ACTION, group(groupJid), groupJid)));
    }

    public CompletableFuture<Optional<String>> getGroupInviteCode(String groupJid) {
        return queue.submit(Interaction.of("get_group_invite_code", Kind.QUERY, group(groupJid), groupJid))
                .thenApply(data -> Optional.ofNullable(Json.str(data, "code")));
    }

    public CompletableFuture<Optional<String>> revokeGroupInviteCode(String groupJid) {
        return queue.submit(Interaction.of("revoke_invite_code", Kind.ACTION, group(groupJid), groupJid))
                .thenApply(data -> Optional.ofNullable(Json.str(data, "code")));
    }

    public CompletableFuture<Group> getGroupInviteInfo(String inviteCodeOrLink) {
        return query("get_invite_info", "code", inviteCodeOrLink).thenApply(Decode::group);
    }

    public CompletableFuture<Optional<String>> joinGroup(String inviteCodeOrLink) {
        JsonObject payload = new JsonObject();
        payload.addProperty("code", Objects.requireNonNull(inviteCodeOrLink, "inviteCodeOrLink"));
        return queue.submit(Interaction.of("join_group", Kind.ACTION, payload, null)).thenApply(data -> Optional.ofNullable(Json.str(data, "groupJid")));
    }

    private CompletableFuture<Void> groupSetting(String groupJid, String setting) {
        JsonObject payload = group(groupJid);
        payload.addProperty("setting", setting);
        return done(queue.submit(Interaction.of("group_setting", Kind.ACTION, payload, groupJid)));
    }

    // ------------------------------------------------------------------ queue and options

    public List<PendingInteraction> pendingInteractions() {
        return queue.pending();
    }

    public boolean cancelInteraction(String interactionId) {
        return queue.cancel(interactionId);
    }

    public int clearQueue() {
        return queue.clear();
    }

    public PacingOptions pacing() {
        return pacing;
    }

    public void setPacing(PacingOptions options) {
        this.pacing = Objects.requireNonNull(options, "options");
        queue.wake();
    }

    public HumanizationOptions humanization() {
        return humanization;
    }

    public void setHumanization(HumanizationOptions options) {
        this.humanization = Objects.requireNonNull(options, "options");
        if (options.alwaysOnline() && isConnected()) {
            startKeepAlive();
        } else if (!options.alwaysOnline()) {
            stopKeepAlive();
        }
    }

    public CallOptions callOptions() {
        return callOptions;
    }

    public void setCallOptions(CallOptions options) {
        this.callOptions = Objects.requireNonNull(options, "options");
    }

    // ------------------------------------------------------------------ bridge plumbing

    private CompletableFuture<JsonObject> createInstance() {
        OrphanBridges.reap(sessionDirectory, host.pid());
        HumanizationOptions signals = humanization;
        JsonObject payload = new JsonObject();
        payload.addProperty("sessionDirectory", sessionDirectory.toString());
        Json.put(payload, "displayName", deviceName);
        payload.addProperty("alwaysOnline", signals.alwaysOnline());
        payload.addProperty("includeRawData", includeRawData);
        payload.addProperty("acceptSince", acceptSince);
        payload.addProperty("readsPerMinute", pacing.maxReadsPerMinute());
        payload.addProperty("minReconnectDelayMs", minReconnectDelay.toMillis());
        payload.addProperty("maxReconnectDelayMs", maxReconnectDelay.toMillis());
        payload.addProperty("maxLoginCycles", maxLoginCycles);
        return control("create_instance", payload, CONTROL_TIMEOUT).thenApply(data -> {
            updateState(ConnectionState.fromWire(Json.str(data, "state")), null, null);
            return data;
        });
    }

    private CompletableFuture<JsonObject> control(String action, JsonObject payload, Duration timeout) {
        return host.request(action, instanceId, payload, timeout);
    }

    private CompletableFuture<JsonObject> query(String action, String field, String value) {
        JsonObject payload = new JsonObject();
        payload.addProperty(field, Objects.requireNonNull(value, field));
        return queue.submit(Interaction.of(action, Kind.QUERY, payload, null));
    }

    private CompletableFuture<Void> action(String action, String field, String value) {
        JsonObject payload = new JsonObject();
        payload.addProperty(field, value);
        return done(queue.submit(Interaction.of(action, Kind.ACTION, payload, null)));
    }

    private void onEvent(String event, JsonObject data) {
        switch (event) {
            case "connection_state_changed" -> onStateEvent(data);
            case "qr_code" -> {
                QrCode qr = new QrCode(Json.str(data, "qr"), (int) Json.number(data, "cycle", 1));
                currentQr = qr;
                notify(qrCodeListeners, listener -> listener.onQrCodeReceived(qr));
            }
            case "message_received" -> onMessage(Decode.message(instanceId, data));
            case "message_updated" -> notify(messageUpdatedListeners, listener -> listener.onMessageUpdated(Decode.messageUpdate(data)));
            case "message_deleted" -> notify(messageDeletedListeners,
                    listener -> listener.onMessageDeleted(Json.str(data, "chatId"), Json.str(data, "messageId")));
            case "reaction_received" -> notify(reactionListeners, listener -> listener.onReaction(Decode.reaction(data)));
            case "group_updated" -> notify(groupUpdateListeners, listener -> listener.onGroupUpdated(Decode.groupUpdate(data)));
            case "presence_updated" -> notify(presenceListeners, listener -> listener.onPresenceUpdated(Decode.presence(data)));
            case "call_received" -> onCall(Decode.call(data));
            case "poll_vote" -> notify(pollVoteListeners, listener -> listener.onPollVote(Decode.pollVote(data)));
            default -> Console.debug("[whatsapp] %s: ignoring bridge event %s", instanceId, event);
        }
    }

    private void onStateEvent(JsonObject data) {
        ConnectionState next = ConnectionState.fromWire(Json.str(data, "state"));
        updateState(next, DisconnectCause.fromWire(Json.str(data, "cause")), Json.str(data, "detail"));
    }

    private void updateState(ConnectionState next, DisconnectCause cause, String detail) {
        ConnectionState previous = state.getAndSet(next);
        if (next != ConnectionState.QR_PENDING) {
            currentQr = null;
        }
        if (next == ConnectionState.CONNECTED) {
            if (humanization.alwaysOnline()) {
                startKeepAlive();
            }
            queue.wake();
        } else {
            stopKeepAlive();
        }
        if (previous != next || cause != null) {
            notify(connectionStateListeners, listener -> listener.onConnectionStateChanged(next, cause, detail));
        }
    }

    private void onMessage(Message message) {
        if (!message.isFromMe()) {
            queue.noteIncoming(message.chatId());
            HumanizationOptions signals = humanization;
            if (signals.readReceipts() == ReadReceiptMode.AUTO) {
                JsonObject payload = new JsonObject();
                payload.addProperty("chatId", message.chatId());
                queue.submit(Interaction.of("mark_read", Kind.RECEIPT, payload, message.chatId())
                        .after(signals.readDelay(ThreadLocalRandom.current())).replacing("read:" + message.chatId()));
            }
            if (signals.autoSubscribePresence()) {
                subscribePresence(message.chatId());
            }
        }
        notify(messageListeners, listener -> listener.onMessageReceived(message));
        if (buttonListeners.isEmpty()) {
            return;
        }
        ButtonReply reply = message.buttonReply();
        Optional<ButtonClick> click = reply != null
                ? Optional.of(new ButtonClick(reply.buttonId(), reply.text(), reply.sourceMessageId(), message))
                : ButtonMenus.match(message);
        click.ifPresent(chosen -> notify(buttonListeners, listener -> listener.onButtonClicked(chosen)));
    }

    private void onCall(IncomingCall call) {
        notify(callListeners, listener -> listener.onCall(call));
        CallOptions options = callOptions;
        if (!options.rejectCalls()) {
            return;
        }
        Duration delay = options.rejectDelay(ThreadLocalRandom.current());
        JsonObject payload = new JsonObject();
        payload.addProperty("callId", call.callId());
        payload.addProperty("from", call.from());
        queue.submit(Interaction.of("reject_call", Kind.CALL, payload, call.chatId()).after(delay))
                .exceptionally(error -> {
                    report(error);
                    return null;
                });
        if (options.replyText() != null && !call.isGroup()) {
            sendText(SendTextRequest.of(call.chatId(), options.replyText())).exceptionally(error -> {
                report(error);
                return null;
            });
        }
    }

    private void startKeepAlive() {
        if (keepAlive != null && !keepAlive.isDone()) {
            return;
        }
        long delay = KEEP_ALIVE_MIN_MS + ThreadLocalRandom.current().nextLong(KEEP_ALIVE_JITTER_MS);
        keepAlive = Threads.TIMER.schedule(() -> {
            keepAlive = null;
            if (closed.get() || state.get() != ConnectionState.CONNECTED || !humanization.alwaysOnline()) {
                return;
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("presence", Presence.AVAILABLE.wireValue());
            queue.submit(Interaction.of("send_presence", Kind.KEEPALIVE, payload, null).replacing("presence:keepalive"));
            startKeepAlive();
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void stopKeepAlive() {
        ScheduledFuture<?> current = keepAlive;
        keepAlive = null;
        if (current != null) {
            current.cancel(false);
        }
    }

    private <L> void notify(List<L> listeners, Consumer<L> call) {
        for (L listener : listeners) {
            try {
                call.accept(listener);
            } catch (RuntimeException e) {
                Console.error("[whatsapp] " + instanceId + ": a listener threw " + e, e);
            }
        }
    }

    private void report(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        WhatsAppException failure = cause instanceof WhatsAppException known ? known
                : new WhatsAppException(instanceId, BridgeErrorCode.INTERNAL_ERROR, cause.getMessage(), cause);
        notify(errorListeners, listener -> listener.onError(failure));
    }

    private static JsonObject target(String chat, String quotedMessageId) {
        JsonObject payload = new JsonObject();
        payload.addProperty("to", chat);
        Json.put(payload, "quotedMessageId", quotedMessageId);
        return payload;
    }

    private static JsonObject ref(MessageRef message) {
        JsonObject payload = new JsonObject();
        payload.addProperty("chatId", Jids.of(message.chatId()));
        payload.addProperty("messageId", message.messageId());
        payload.addProperty("fromMe", message.fromMe());
        Json.put(payload, "participant", message.participant());
        return payload;
    }

    private static JsonObject chat(String chatId, String change) {
        JsonObject payload = new JsonObject();
        payload.addProperty("chatId", Jids.of(chatId));
        payload.addProperty("change", change);
        return payload;
    }

    private static JsonObject group(String groupJid) {
        JsonObject payload = new JsonObject();
        payload.addProperty("groupJid", Objects.requireNonNull(groupJid, "groupJid"));
        return payload;
    }

    // The bridge reads the file: a path that does not exist fails here, with a clear message, instead of there.
    private static String existing(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute)) {
            throw new UncheckedIOException(new IOException("file not found: " + absolute));
        }
        return absolute.toString();
    }

    private static int length(String text) {
        return text == null ? 0 : text.length();
    }

    private static CompletableFuture<String> messageId(CompletableFuture<JsonObject> result) {
        return result.thenApply(data -> Json.str(data, "messageId"));
    }

    private static CompletableFuture<Void> done(CompletableFuture<JsonObject> result) {
        return result.thenApply(ignored -> null);
    }

    private final class Gateway implements InteractionQueue.Gateway {

        @Override
        public boolean isReady() {
            return state.get() == ConnectionState.CONNECTED && host.isOpen();
        }

        @Override
        public JsonObject call(String action, JsonObject payload, Duration timeout) {
            try {
                return host.request(action, instanceId, payload, timeout).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new QueueException(instanceId, BridgeErrorCode.QUEUE_CLOSED, "queue stopped");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof WhatsAppException known) {
                    throw known;
                }
                throw new WhatsAppException(instanceId, BridgeErrorCode.INTERNAL_ERROR, String.valueOf(cause), cause);
            }
        }
    }

    private final class Attachment implements BridgeHost.Attachment {

        @Override
        public void onBridgeEvent(String event, JsonObject data) {
            boolean droppable = "presence_updated".equals(event) || "message_updated".equals(event);
            inbox.submit(() -> onEvent(event, data), droppable);
        }

        @Override
        public void onBridgeReady() {
            if (!wantConnected || closed.get()) {
                return;
            }
            createInstance()
                    .thenCompose(ignored -> control("connect", new JsonObject(), CONTROL_TIMEOUT))
                    .whenComplete((ignored, error) -> {
                        if (error != null) {
                            report(error);
                        }
                        queue.wake();
                    });
        }

        @Override
        public void onBridgeLost(DisconnectCause cause, String detail) {
            inbox.submit(() -> updateState(ConnectionState.RECONNECTING, cause, detail), false);
        }
    }

    public static final class Builder {

        private String instanceId;
        private Path sessionDirectory;
        private String deviceName;
        private boolean includeRawData;
        private int maxLoginCycles = 5;
        private Duration minReconnectDelay = Duration.ofSeconds(1);
        private Duration maxReconnectDelay = Duration.ofSeconds(30);
        private PacingOptions pacing = PacingOptions.defaults();
        private HumanizationOptions humanization = HumanizationOptions.defaults();
        private CallOptions callOptions = CallOptions.defaults();
        private BridgeOptions bridgeOptions = BridgeOptions.defaults();

        private Builder() {
        }

        public Builder instanceId(String id) {
            this.instanceId = id;
            return this;
        }

        public Builder sessionDirectory(Path directory) {
            this.sessionDirectory = directory;
            return this;
        }

        public Builder sessionDirectory(String directory) {
            this.sessionDirectory = Path.of(directory);
            return this;
        }

        public Builder deviceName(String name) {
            this.deviceName = name;
            return this;
        }

        public Builder includeRawData(boolean include) {
            this.includeRawData = include;
            return this;
        }

        public Builder maxLoginCycles(int cycles) {
            if (cycles < 1) {
                throw new IllegalArgumentException("maxLoginCycles must be at least 1");
            }
            this.maxLoginCycles = cycles;
            return this;
        }

        public Builder reconnectDelay(Duration min, Duration max) {
            if (min.isNegative() || max.compareTo(min) < 0) {
                throw new IllegalArgumentException("reconnect delay must satisfy 0 <= min <= max");
            }
            this.minReconnectDelay = min;
            this.maxReconnectDelay = max;
            return this;
        }

        public Builder pacing(PacingOptions options) {
            this.pacing = Objects.requireNonNull(options, "options");
            return this;
        }

        public Builder humanization(HumanizationOptions options) {
            this.humanization = Objects.requireNonNull(options, "options");
            return this;
        }

        public Builder calls(CallOptions options) {
            this.callOptions = Objects.requireNonNull(options, "options");
            return this;
        }

        public Builder bridge(BridgeOptions options) {
            this.bridgeOptions = Objects.requireNonNull(options, "options");
            return this;
        }

        public Builder bridge(Function<BridgeOptions, BridgeOptions> customize) {
            this.bridgeOptions = customize.apply(bridgeOptions);
            return this;
        }

        public WhatsAppClient build() {
            if (instanceId == null || instanceId.isBlank()) {
                throw new IllegalArgumentException("instanceId is required");
            }
            if (sessionDirectory == null) {
                throw new IllegalArgumentException("sessionDirectory is required");
            }
            return new WhatsAppClient(this);
        }
    }
}
