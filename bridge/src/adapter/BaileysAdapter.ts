import { rm } from 'node:fs/promises';
import makeWASocket, {
    DisconnectReason,
    useMultiFileAuthState,
    downloadMediaMessage,
    type WASocket,
    type WAMessage,
    type GroupMetadata,
    type Contact as BaileysContact,
    type AnyMessageContent,
    type WAPresence
} from 'baileys';
import { Boom } from '@hapi/boom';
import type { Logger } from '../util/logger.js';
import { MessageCache } from './MessageCache.js';
import { LidDirectory } from './LidDirectory.js';
import { toAbstractMessage, type AbstractMessage } from './MessageMapper.js';
import { BridgeCommandError, ErrorCode } from '../protocol/errors.js';
import * as Content from './ContentBuilder.js';

export type ConnectionStateName =
    | 'DISCONNECTED'
    | 'CONNECTING'
    | 'QR_PENDING'
    | 'PAIRING_CODE_PENDING'
    | 'CONNECTED'
    | 'RECONNECTING'
    | 'LOGGED_OUT'
    | 'FAILED';

export type DisconnectCause = 'USER_LOGOUT' | 'CONNECTION_LOST' | 'WHATSAPP_RESTART' | 'AUTH_FAILURE' | 'UNKNOWN';

export interface BaileysAdapterCallbacks {
    onConnectionStateChanged(state: ConnectionStateName, cause: DisconnectCause | null, detail: string | null): void;
    onQr(qr: string): void;
    onPairingCode(code: string): void;
    onMessage(message: AbstractMessage): void;
    onMessageUpdated(chatId: string, messageId: string, updates: unknown): void;
    onMessageDeleted(chatId: string, messageId: string): void;
    onReaction(chatId: string, messageId: string, senderId: string, emoji: string | null): void;
    onGroupUpdated(groupJid: string, updates: unknown): void;
    onPresenceUpdated(chatId: string, presences: unknown): void;
}

export interface AdapterOptions {
    instanceId: string;
    sessionDirectory: string;
    displayName: string;
    markOnlineOnConnect: boolean;
    minReconnectDelayMs: number;
    maxReconnectDelayMs: number;
}

const DISCONNECT_CAUSE_BY_STATUS_CODE: Record<number, DisconnectCause> = {
    [DisconnectReason.loggedOut]: 'USER_LOGOUT',
    [DisconnectReason.connectionReplaced]: 'USER_LOGOUT',
    [DisconnectReason.badSession]: 'AUTH_FAILURE',
    [DisconnectReason.multideviceMismatch]: 'AUTH_FAILURE',
    [DisconnectReason.forbidden]: 'AUTH_FAILURE',
    [DisconnectReason.restartRequired]: 'WHATSAPP_RESTART',
    // DisconnectReason.timedOut shares the same numeric value (408) as
    // connectionLost, so a single entry covers both.
    [DisconnectReason.connectionLost]: 'CONNECTION_LOST',
    [DisconnectReason.connectionClosed]: 'CONNECTION_LOST',
    [DisconnectReason.unavailableService]: 'CONNECTION_LOST'
};

/**
 * Closures after which the credentials on disk are unusable and must be
 * discarded.
 *
 * Baileys only emits a QR code when it starts with no valid credentials. If a
 * dead session is left on disk, every subsequent connect() reloads it, gets
 * rejected by WhatsApp for the same reason, and returns to LOGGED_OUT without
 * ever producing a QR - leaving the operator with a "reconnect" button that
 * can never work. Clearing the directory is what makes the next connect()
 * fall back to pairing.
 *
 * `connectionReplaced` is deliberately absent: there the credentials are still
 * valid, another socket simply took over the session, so reconnecting later
 * works and wiping them would force a pointless re-pairing.
 */
const CREDENTIALS_INVALIDATED_STATUS_CODES: ReadonlySet<number> = new Set([
    DisconnectReason.loggedOut,
    DisconnectReason.badSession,
    DisconnectReason.multideviceMismatch,
    DisconnectReason.forbidden
]);

/**
 * Owns exactly one WhatsApp connection (one Baileys socket) and translates
 * between Baileys' native API/events and the abstract shapes the rest of
 * the bridge (and, through it, the Java SDK) speak. Nothing outside this
 * file should import from the `baileys` package directly - that is the
 * "camada de adaptacao" the SDK spec asks for.
 */
export class BaileysAdapter {
    private socket: WASocket | null = null;
    private saveCreds: (() => Promise<void>) | null = null;
    private state: ConnectionStateName = 'DISCONNECTED';
    private currentQr: string | null = null;
    private reconnectAttempts = 0;
    private reconnectTimer: NodeJS.Timeout | null = null;
    /**
     * Which deliberate local action (if any) is in flight. Not a plain boolean:
     * handleClose() needs to know not just *that* the closure was local, but
     * which target state it implies (disconnect -> DISCONNECTED, logout ->
     * LOGGED_OUT), since Baileys' own 'connection.update' close event can fire
     * either before or after the explicit setState() call in disconnect()/logout()
     * (emit() is synchronous, but whether socket.end()/logout() triggers it
     * within the same tick is not something this code controls) - both paths
     * must therefore agree on the same outcome regardless of which runs first.
     */
    private closedByUser: false | 'disconnect' | 'logout' = false;
    /**
     * In-flight deletion of an invalidated session, awaited by openSocket().
     *
     * handleClose() reacts to a synchronous Baileys event, so it cannot await
     * the removal itself; without this gate a fast reconnect could read the
     * credentials back while they are still being deleted.
     */
    private sessionCleanup: Promise<void> | null = null;
    private readonly messageCache = new MessageCache();

    /** Traducao LID -> telefone; ver LidDirectory para o porque. */
    private readonly lids = new LidDirectory();

    constructor(
        private readonly options: AdapterOptions,
        private readonly logger: Logger,
        private readonly callbacks: BaileysAdapterCallbacks
    ) {}

    getState(): ConnectionStateName {
        return this.state;
    }

    getCurrentQr(): string | null {
        return this.currentQr;
    }

    getUserId(): string | null {
        return this.socket?.user?.id ?? null;
    }

    async connect(): Promise<void> {
        this.closedByUser = false;
        await this.openSocket();
    }

    private async openSocket(): Promise<void> {
        this.setState('CONNECTING', null, null);

        // A session invalidated by WhatsApp is deleted asynchronously; reading
        // the credentials before that finishes would load the very files that
        // are on their way out.
        if (this.sessionCleanup) {
            await this.sessionCleanup;
            this.sessionCleanup = null;
        }

        const { state, saveCreds } = await useMultiFileAuthState(this.options.sessionDirectory);
        this.saveCreds = saveCreds;

        const socket = makeWASocket({
            auth: state,
            logger: this.logger as never,
            browser: [this.options.displayName, 'Chrome', '1.0.0'],
            markOnlineOnConnect: this.options.markOnlineOnConnect,
            syncFullHistory: false,
            generateHighQualityLinkPreview: false
        });
        this.socket = socket;

        socket.ev.on('creds.update', async () => {
            await this.saveCreds?.();
        });

        socket.ev.on('connection.update', (update) => this.handleConnectionUpdate(update));

        socket.ev.on('messages.upsert', ({ messages, type }) => {
            if (type !== 'notify' && type !== 'append') return;
            for (const msg of messages) {
                // O par LID/telefone vem no proprio stanza: aprender antes de
                // mapear e o que faz a PRIMEIRA mensagem ja cair na conversa
                // certa, e nao so a segunda.
                this.learnLids(msg);
                this.messageCache.put(msg);
                if (!msg.message) continue;
                this.callbacks.onMessage(toAbstractMessage(msg, this.lids));
            }
        });

        // Contatos trazem os dois enderecos do mesmo contato de uma vez.
        const aprenderContatos = (contatos: { id?: string; lid?: string; jid?: string }[]) => {
            for (const c of contatos ?? []) {
                this.lids.remember(c.lid ?? c.id, c.jid ?? c.id);
            }
        };
        socket.ev.on('contacts.upsert', aprenderContatos);
        socket.ev.on('contacts.update', aprenderContatos);

        socket.ev.on('messages.update', (updates) => {
            for (const update of updates) {
                const chatId = update.key.remoteJid;
                const messageId = update.key.id;
                if (!chatId || !messageId) continue;
                this.callbacks.onMessageUpdated(chatId, messageId, update.update);
            }
        });

        socket.ev.on('messages.delete', (payload) => {
            if ('keys' in payload) {
                for (const key of payload.keys) {
                    if (key.remoteJid && key.id) {
                        this.callbacks.onMessageDeleted(key.remoteJid, key.id);
                    }
                }
            }
        });

        socket.ev.on('messages.reaction', (reactions) => {
            for (const r of reactions) {
                const chatId = r.key.remoteJid;
                const messageId = r.key.id;
                if (!chatId || !messageId) continue;
                this.callbacks.onReaction(chatId, messageId, r.reaction.key?.participant ?? chatId, r.reaction.text ?? null);
            }
        });

        socket.ev.on('groups.update', (updates) => {
            for (const update of updates) {
                if (update.id) {
                    this.callbacks.onGroupUpdated(update.id, update);
                }
            }
        });

        socket.ev.on('group-participants.update', (event) => {
            this.callbacks.onGroupUpdated(event.id, event);
        });

        socket.ev.on('presence.update', (event) => {
            this.callbacks.onPresenceUpdated(event.id, event.presences);
        });
    }

    private handleConnectionUpdate(update: {
        connection?: string;
        qr?: string;
        lastDisconnect?: { error?: Error };
    }): void {
        if (update.qr) {
            this.currentQr = update.qr;
            this.setState('QR_PENDING', null, null);
            this.callbacks.onQr(update.qr);
            return;
        }

        if (update.connection === 'open') {
            this.currentQr = null;
            this.reconnectAttempts = 0;
            this.setState('CONNECTED', null, null);
            return;
        }

        if (update.connection === 'close') {
            this.handleClose(update.lastDisconnect?.error);
        }
    }

    private handleClose(error: Error | undefined): void {
        const boom = error as Boom | undefined;
        const statusCode = boom?.output?.statusCode;
        const cause: DisconnectCause =
            (statusCode !== undefined ? DISCONNECT_CAUSE_BY_STATUS_CODE[statusCode] : undefined) ?? 'UNKNOWN';
        const detail = error?.message ?? null;

        this.socket = null;

        if (this.closedByUser) {
            // Deliberate local closure: report USER_LOGOUT/target-state deterministically
            // rather than trusting the (possibly absent, since a locally-initiated close may
            // carry no real disconnect-reason error at all) status-code-derived cause above -
            // see the closedByUser field comment for why this must agree with disconnect()/logout().
            const targetState: ConnectionStateName = this.closedByUser === 'logout' ? 'LOGGED_OUT' : 'DISCONNECTED';
            this.setState(targetState, 'USER_LOGOUT', detail ?? `${this.closedByUser}() chamado explicitamente`);
            return;
        }

        if (cause === 'USER_LOGOUT' || cause === 'AUTH_FAILURE') {
            if (statusCode !== undefined && CREDENTIALS_INVALIDATED_STATUS_CODES.has(statusCode)) {
                this.discardInvalidatedSession(statusCode);
            }
            this.setState(cause === 'USER_LOGOUT' ? 'LOGGED_OUT' : 'FAILED', cause, detail);
            this.logger.warn({ cause, detail }, 'connection closed without automatic reconnect');
            return;
        }

        this.setState('RECONNECTING', cause, detail);
        this.scheduleReconnect(cause === 'WHATSAPP_RESTART');
    }

    /**
     * Drops credentials WhatsApp has just rejected, so the next connect()
     * starts a fresh pairing and emits a QR code instead of replaying the same
     * rejection forever.
     *
     * Fire-and-forget by necessity (the caller reacts to a synchronous event),
     * but the promise is kept in `sessionCleanup` so openSocket() waits for it.
     * A failure here is logged rather than thrown: the connection is already
     * down, and the state change still has to reach the operator.
     */
    private discardInvalidatedSession(statusCode: number): void {
        this.logger.warn({ statusCode }, 'credenciais invalidadas pelo WhatsApp; limpando sessao para permitir novo pareamento');
        this.sessionCleanup = rm(this.options.sessionDirectory, { recursive: true, force: true }).catch((err) => {
            this.logger.error({ err }, 'falha ao limpar a sessao invalidada; o proximo connect pode continuar sendo recusado');
        });
    }

    private scheduleReconnect(immediate: boolean): void {
        if (this.reconnectTimer) {
            clearTimeout(this.reconnectTimer);
        }

        const delay = immediate
            ? 0
            : Math.min(
                  this.options.maxReconnectDelayMs,
                  this.options.minReconnectDelayMs * Math.pow(2, this.reconnectAttempts)
              ) + Math.floor(Math.random() * 250);

        this.reconnectAttempts += 1;
        this.logger.info({ delay, attempt: this.reconnectAttempts }, 'scheduling reconnect');

        this.reconnectTimer = setTimeout(() => {
            this.openSocket().catch((err) => {
                this.logger.error({ err }, 'reconnect attempt failed');
                this.setState('RECONNECTING', 'CONNECTION_LOST', (err as Error).message);
                this.scheduleReconnect(false);
            });
        }, delay);
    }

    private setState(state: ConnectionStateName, cause: DisconnectCause | null, detail: string | null): void {
        const previous = this.state;
        this.state = state;
        if (previous !== state) {
            this.callbacks.onConnectionStateChanged(state, cause, detail);
        }
    }

    async requestPairingCode(phoneNumber: string): Promise<string> {
        if (!this.socket) {
            throw new BridgeCommandError(ErrorCode.NOT_CONNECTED, 'Instancia nao esta conectando; chame connect antes');
        }
        this.setState('PAIRING_CODE_PENDING', null, null);
        const code = await this.socket.requestPairingCode(phoneNumber);
        this.callbacks.onPairingCode(code);
        return code;
    }

    async disconnect(): Promise<void> {
        this.closedByUser = 'disconnect';
        if (this.reconnectTimer) {
            clearTimeout(this.reconnectTimer);
            this.reconnectTimer = null;
        }
        this.socket?.end(undefined);
        this.socket = null;
        // Also set explicitly (not just left to handleClose()'s reaction to Baileys' own
        // 'connection.update' close event): if there was no active socket, that event never fires.
        this.setState('DISCONNECTED', 'USER_LOGOUT', 'disconnect() chamado explicitamente');
    }

    async logout(): Promise<void> {
        this.closedByUser = 'logout';
        if (this.reconnectTimer) {
            clearTimeout(this.reconnectTimer);
            this.reconnectTimer = null;
        }
        try {
            await this.socket?.logout();
        } catch (err) {
            this.logger.warn({ err }, 'logout() no baileys falhou, prosseguindo com limpeza local');
        }
        this.socket = null;
        await rm(this.options.sessionDirectory, { recursive: true, force: true });
        // Also set explicitly, same reasoning as disconnect() above.
        this.setState('LOGGED_OUT', 'USER_LOGOUT', 'logout() chamado explicitamente');
    }

    /**
     * Extrai do stanza os pares LID/telefone que o Baileys expoe em
     * `senderPn`/`participantPn`.
     */
    private learnLids(msg: WAMessage): void {
        const key = msg.key as {
            remoteJid?: string | null;
            participant?: string | null;
            senderLid?: string;
            senderPn?: string;
            participantLid?: string;
            participantPn?: string;
        };
        this.lids.remember(key.senderLid ?? key.remoteJid, key.senderPn);
        this.lids.remember(key.participantLid ?? key.participant, key.participantPn);
    }

    private requireSocket(): WASocket {
        if (!this.socket || this.state !== 'CONNECTED') {
            throw new BridgeCommandError(ErrorCode.NOT_CONNECTED, `Instancia ${this.options.instanceId} nao esta conectada`);
        }
        return this.socket;
    }

    private resolveQuoted(chatId: string, quotedMessageId: string | undefined): WAMessage | undefined {
        if (!quotedMessageId) return undefined;
        const quoted = this.messageCache.get(chatId, quotedMessageId);
        if (!quoted) {
            throw new BridgeCommandError(
                ErrorCode.MEDIA_NOT_FOUND,
                `Mensagem ${quotedMessageId} nao esta mais em cache para ser citada (janela de retencao expirada)`
            );
        }
        return quoted;
    }

    private async send(to: string, content: AnyMessageContent, quotedMessageId?: string): Promise<string> {
        const socket = this.requireSocket();
        const quoted = this.resolveQuoted(to, quotedMessageId);
        try {
            const result = await socket.sendMessage(to, content, { quoted });
            if (!result?.key.id) {
                throw new BridgeCommandError(ErrorCode.SEND_FAILED, 'Baileys nao retornou um id de mensagem');
            }
            return result.key.id;
        } catch (err) {
            if (err instanceof BridgeCommandError) throw err;
            throw new BridgeCommandError(ErrorCode.SEND_FAILED, (err as Error).message);
        }
    }

    sendText(to: string, payload: Content.SendTextPayload, quotedMessageId?: string): Promise<string> {
        return this.send(to, Content.buildTextContent(payload), quotedMessageId);
    }

    sendImage(to: string, payload: Content.SendImagePayload, quotedMessageId?: string): Promise<string> {
        return this.send(to, Content.buildImageContent(payload), quotedMessageId);
    }

    sendVideo(to: string, payload: Content.SendVideoPayload, quotedMessageId?: string): Promise<string> {
        return this.send(to, Content.buildVideoContent(payload), quotedMessageId);
    }

    sendAudio(to: string, payload: Content.SendAudioPayload, quotedMessageId?: string): Promise<string> {
        return this.send(to, Content.buildAudioContent(payload), quotedMessageId);
    }

    sendDocument(to: string, payload: Content.SendDocumentPayload, quotedMessageId?: string): Promise<string> {
        return this.send(to, Content.buildDocumentContent(payload), quotedMessageId);
    }

    sendSticker(to: string, payload: Content.SendStickerPayload, quotedMessageId?: string): Promise<string> {
        return this.send(to, Content.buildStickerContent(payload), quotedMessageId);
    }

    sendLocation(to: string, payload: Content.SendLocationPayload, quotedMessageId?: string): Promise<string> {
        return this.send(to, Content.buildLocationContent(payload), quotedMessageId);
    }

    sendContact(to: string, payload: Content.SendContactPayload, quotedMessageId?: string): Promise<string> {
        return this.send(to, Content.buildContactContent(payload), quotedMessageId);
    }

    /**
     * Publishes our own presence in a chat ('composing' shows the recipient
     * the "typing..." indicator, 'recording' the voice-note one, 'paused'
     * clears it, 'available'/'unavailable' set online/offline).
     *
     * WhatsApp only routes a chat presence to clients that have subscribed to
     * it, so `presenceSubscribe` is issued first - without it the indicator is
     * silently dropped by the server for a chat the recipient is not already
     * watching.
     *
     * Best-effort by design: presence is decoration around a send, never the
     * point of it, so a failure here is reported but must not be able to abort
     * the message that follows.
     */
    /**
     * Publishes a presence ("composing", "paused", ...) into a conversation.
     *
     * The `available` step is not optional. Instances usually connect with
     * `markOnlineOnConnect: false` so the number does not sit online all day,
     * but WhatsApp will not relay a typing indicator from a client that has
     * declared itself unavailable — the update is accepted and silently
     * dropped. Announcing availability first is what makes "digitando..."
     * actually appear on the recipient's screen; the caller can return to
     * `unavailable` afterwards.
     */
    async sendPresence(chatId: string, presence: WAPresence): Promise<void> {
        const socket = this.requireSocket();
        try {
            if (presence === 'composing' || presence === 'recording') {
                await socket.sendPresenceUpdate('available');
                await socket.presenceSubscribe(chatId);
            }
            await socket.sendPresenceUpdate(presence, chatId);
        } catch (err) {
            throw new BridgeCommandError(ErrorCode.SEND_FAILED, (err as Error).message);
        }
    }

    async sendReaction(payload: Content.SendReactionPayload): Promise<void> {
        const socket = this.requireSocket();
        try {
            await socket.sendMessage(payload.chatId, Content.buildReactionContent(payload));
        } catch (err) {
            throw new BridgeCommandError(ErrorCode.SEND_FAILED, (err as Error).message);
        }
    }

    async downloadMedia(chatId: string, messageId: string): Promise<{ base64: string; mimetype: string }> {
        this.requireSocket();
        const cached = this.messageCache.get(chatId, messageId);
        if (!cached) {
            throw new BridgeCommandError(
                ErrorCode.MEDIA_NOT_FOUND,
                `Mensagem ${messageId} nao esta mais em cache (janela de retencao expirada)`
            );
        }
        try {
            const buffer = (await downloadMediaMessage(cached, 'buffer', {})) as Buffer;
            const mimetype = this.extractMimetype(cached);
            return { base64: buffer.toString('base64'), mimetype };
        } catch (err) {
            throw new BridgeCommandError(ErrorCode.MEDIA_NOT_FOUND, (err as Error).message);
        }
    }

    private extractMimetype(msg: WAMessage): string {
        const m = msg.message;
        return (
            m?.imageMessage?.mimetype ??
            m?.videoMessage?.mimetype ??
            m?.audioMessage?.mimetype ??
            m?.documentMessage?.mimetype ??
            m?.stickerMessage?.mimetype ??
            'application/octet-stream'
        );
    }

    /**
     * Identity of the account this instance is logged in as.
     *
     * Baileys exposes it as `socket.user`, whose `id` carries the device
     * suffix ("5562999...:12@s.whatsapp.net"); the suffix is stripped here so
     * callers get the same JID shape every other command uses. `name` is the
     * WhatsApp profile name, which may legitimately be absent.
     */
    async getMe(): Promise<{ jid: string | null; phoneNumber: string | null; name: string | null }> {
        const socket = this.requireSocket();
        const user = socket.user;
        if (!user?.id) {
            return { jid: null, phoneNumber: null, name: null };
        }
        const digits = (user.id.split(':')[0] ?? '').split('@')[0]?.replace(/[^0-9]/g, '') ?? '';
        return {
            jid: digits.length > 0 ? `${digits}@s.whatsapp.net` : null,
            phoneNumber: digits.length > 0 ? digits : null,
            name: user.name ?? user.verifiedName ?? null
        };
    }

    async getContact(jid: string): Promise<{ jid: string; name: string | null; status: string | null; profilePictureUrl: string | null; onWhatsApp: boolean }> {
        const socket = this.requireSocket();
        const results = await socket.onWhatsApp(jid);
        const exists = results?.[0]?.exists != null && results[0].exists !== false;
        const resolvedJid = results?.[0]?.jid ?? jid;
        // A consulta devolve os dois enderecos do contato: aproveita para
        // aprender o par antes mesmo de ele mandar a primeira mensagem.
        this.lids.remember((results?.[0] as { lid?: string } | undefined)?.lid, resolvedJid);

        let profilePictureUrl: string | null = null;
        try {
            profilePictureUrl = (await socket.profilePictureUrl(resolvedJid, 'image')) ?? null;
        } catch {
            profilePictureUrl = null;
        }

        let status: string | null = null;
        try {
            const statusResult = await socket.fetchStatus(resolvedJid);
            const first = statusResult?.[0] as unknown as { status?: { status?: string } } | undefined;
            status = first?.status?.status ?? null;
        } catch {
            status = null;
        }

        return { jid: resolvedJid, name: null, status, profilePictureUrl, onWhatsApp: exists };
    }

    getGroupMetadata(jid: string): Promise<GroupMetadata> {
        return this.requireSocket().groupMetadata(jid);
    }

    async listGroups(): Promise<GroupMetadata[]> {
        const map = await this.requireSocket().groupFetchAllParticipating();
        return Object.values(map);
    }

    createGroup(subject: string, participants: string[]): Promise<GroupMetadata> {
        return this.requireSocket().groupCreate(subject, participants);
    }

    updateGroupParticipants(
        groupJid: string,
        participants: string[],
        action: 'add' | 'remove' | 'promote' | 'demote'
    ): Promise<unknown> {
        return this.requireSocket().groupParticipantsUpdate(groupJid, participants, action);
    }

    updateGroupSubject(groupJid: string, subject: string): Promise<void> {
        return this.requireSocket().groupUpdateSubject(groupJid, subject);
    }

    updateGroupDescription(groupJid: string, description: string): Promise<void> {
        return this.requireSocket().groupUpdateDescription(groupJid, description);
    }

    leaveGroup(groupJid: string): Promise<void> {
        return this.requireSocket().groupLeave(groupJid);
    }

    getGroupInviteCode(groupJid: string): Promise<string | undefined> {
        return this.requireSocket().groupInviteCode(groupJid);
    }
}

export type { AbstractMessage, BaileysContact };
