import { createHash } from 'node:crypto';
import { createWriteStream } from 'node:fs';
import { rename, stat, unlink } from 'node:fs/promises';
import { join } from 'node:path';
import { pipeline } from 'node:stream/promises';
import makeWASocket, {
    DisconnectReason,
    decryptPollVote,
    downloadMediaMessage,
    generateWAMessageFromContent,
    jidNormalizedUser,
    normalizeMessageContent,
    proto,
    type AnyMessageContent,
    type BaileysEventMap,
    type GroupMetadata,
    type WAMessage,
    type WAPresence,
    type WASocket
} from 'baileys';
import type { Boom } from '@hapi/boom';
import { baileysLogger, forgetProtocolNoise, protocolNoise, type Logger } from '../util/logger.js';
import { BoundedMap, TtlCache } from '../util/TtlCache.js';
import { useDurableAuthState, wipeCredentials } from './AuthState.js';
import { ReadBudget } from './ReadBudget.js';
import { RecentMessages, SentMessageStore } from './MessageStore.js';
import { LidDirectory } from './LidDirectory.js';
import { resolveWhatsAppVersion } from './WaVersion.js';
import { IGNORED_CONTENT, contentTypeOf, toAbstractMessage, type AbstractMessage } from './MessageMapper.js';
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

export type DisconnectCause =
    | 'USER_LOGOUT'
    | 'CONNECTION_LOST'
    | 'WHATSAPP_RESTART'
    | 'AUTH_FAILURE'
    | 'CONNECTION_REPLACED'
    | 'LOGIN_TIMEOUT'
    | 'UNKNOWN';

export interface IncomingCall {
    callId: string;
    from: string;
    chatId: string;
    isVideo: boolean;
    isGroup: boolean;
    timestamp: number;
}

export interface AdapterCallbacks {
    onConnectionStateChanged(state: ConnectionStateName, cause: DisconnectCause | null, detail: string | null): void;
    onQr(qr: string, cycle: number): void;
    onPairingCode(code: string): void;
    onMessage(message: AbstractMessage): void;
    onMessageUpdated(update: Record<string, unknown>): void;
    onMessageDeleted(chatId: string, messageId: string): void;
    onReaction(reaction: Record<string, unknown>): void;
    onGroupUpdated(update: Record<string, unknown>): void;
    onPresenceUpdated(update: Record<string, unknown>): void;
    onCall(call: IncomingCall): void;
    onPollVote(vote: Record<string, unknown>): void;
}

export type ChatChange = 'mute' | 'unmute' | 'pin' | 'unpin' | 'archive' | 'unarchive' | 'mark_unread';

export type PrivacySetting = 'last_seen' | 'online' | 'profile_picture' | 'status' | 'read_receipts' | 'groups_add';

const PRIVACY_VALUES: Record<PrivacySetting, ReadonlySet<string>> = {
    last_seen: new Set(['all', 'contacts', 'contact_blacklist', 'none']),
    online: new Set(['all', 'match_last_seen']),
    profile_picture: new Set(['all', 'contacts', 'contact_blacklist', 'none']),
    status: new Set(['all', 'contacts', 'contact_blacklist', 'none']),
    read_receipts: new Set(['all', 'none']),
    groups_add: new Set(['all', 'contacts', 'contact_blacklist'])
};

export interface AdapterOptions {
    instanceId: string;
    sessionDirectory: string;
    browser: [string, string, string];
    alwaysOnline: boolean;
    includeRawData: boolean;
    /** Epoch seconds; anything older never reaches the application. */
    acceptSince: number;
    readsPerMinute: number;
    minReconnectDelayMs: number;
    maxReconnectDelayMs: number;
    maxLoginCycles: number;
}

const CAUSE_BY_STATUS: Record<number, DisconnectCause> = {
    [DisconnectReason.loggedOut]: 'USER_LOGOUT',
    // 440 is not a logout: another client took the session and the credentials are still good.
    [DisconnectReason.connectionReplaced]: 'CONNECTION_REPLACED',
    [DisconnectReason.multideviceMismatch]: 'AUTH_FAILURE',
    [DisconnectReason.forbidden]: 'AUTH_FAILURE',
    [DisconnectReason.restartRequired]: 'WHATSAPP_RESTART',
    [DisconnectReason.connectionLost]: 'CONNECTION_LOST',
    [DisconnectReason.connectionClosed]: 'CONNECTION_LOST',
    [DisconnectReason.unavailableService]: 'CONNECTION_LOST',
    // 500 is a server-side stream error, not invalid credentials: wiping on it forced a needless re-pair.
    [DisconnectReason.badSession]: 'CONNECTION_LOST'
};

/**
 * Codes after which WhatsApp will only ever answer the stored credentials with
 * the same refusal, so they are wiped to let the next connect() show a QR code.
 * 403 is not here (an account restriction can lift, and re-pairing a flagged
 * account is the worst possible move), nor 440 or 500.
 */
const CREDENTIALS_REVOKED: ReadonlySet<number> = new Set([DisconnectReason.loggedOut, DisconnectReason.multideviceMismatch]);

/** Shorter than Baileys' 30 s: a NAT drops an idle connection sooner, and each drop costs a full handshake. */
const KEEP_ALIVE_MS = 20_000;
const CONNECT_TIMEOUT_MS = 30_000;
const QUERY_TIMEOUT_MS = 45_000;
const SEND_TIMEOUT_MS = 100_000;
const PAIRING_READY_TIMEOUT_MS = 20_000;

/** A 515 asks for an immediate restart once, right after pairing; repeated, it must back off. */
const MAX_IMMEDIATE_RECONNECTS = 3;
/** Another client took the account: coming back fast starts a reconnect war that reads as automation. */
const REPLACED_RETRY_MS = 5 * 60_000;
/** After this many failures in a row the retries space out to the cool-down below. */
const FAILURES_BEFORE_COOLDOWN = 10;
const COOLDOWN_MS = 5 * 60_000;

const SWEEP_INTERVAL_MS = 30 * 60_000;

const HISTORY_KEPT_FOR_LID_MAPPINGS: ReadonlySet<number> = new Set([
    proto.HistorySync.HistorySyncType.INITIAL_BOOTSTRAP,
    proto.HistorySync.HistorySyncType.PUSH_NAME,
    proto.HistorySync.HistorySyncType.NON_BLOCKING_DATA
]);

const KNOWN_NUMBER_TTL_MS = 24 * 60 * 60_000;
/** Shorter: a number created today must not stay "unknown" all day. */
const UNKNOWN_NUMBER_TTL_MS = 6 * 60 * 60_000;
const GROUP_METADATA_TTL_MS = 5 * 60_000;

/**
 * Owns one WhatsApp account: at most one Baileys socket at a time, its
 * reconnection, and the translation between Baileys and the bridge protocol.
 * Nothing outside this file imports the socket.
 *
 * Reading rules that keep the account safe: no history sync, no contact or
 * chat import, no message older than `acceptSince` delivered, and every query
 * charged to a per-minute budget. What it remembers of received messages is a
 * short, stripped window (see MessageStore).
 */
export class BaileysAdapter {
    private socket: WASocket | null = null;
    private state: ConnectionStateName = 'DISCONNECTED';
    private currentQr: string | null = null;
    private generation = 0;
    private subscriptions: Array<() => void> = [];
    private opening: Promise<void> | null = null;
    private sessionWipe: Promise<void> | null = null;
    private reconnectTimer: NodeJS.Timeout | null = null;
    private reconnectAttempts = 0;
    private immediateReconnects = 0;
    private loginCycles = 0;
    private qrInCycle = 0;
    private closedByUser: false | 'disconnect' | 'logout' = false;
    private available = false;
    private readyForPairing: Promise<void> | null = null;
    private markReady: (() => void) | null = null;
    /** Kept across sockets: Baileys renews the socket while the operator is still typing the code. */
    private pairing: { phone: string; code: string } | null = null;
    private pairingRegisteredOn: WASocket | null = null;
    private backupCreds: (() => Promise<void>) | null = null;

    private readonly baileysLog: Logger;
    private readonly reads: ReadBudget;
    private readonly lids = new LidDirectory();
    private readonly recent = new RecentMessages();
    private readonly sent: SentMessageStore;
    private readonly subscribedPresence = new BoundedMap<string, true>(5_000);
    private readonly knownNumbers = new TtlCache(KNOWN_NUMBER_TTL_MS, 2_500);
    private readonly unknownNumbers = new TtlCache(UNKNOWN_NUMBER_TTL_MS, 2_500);
    private readonly groups = new TtlCache(GROUP_METADATA_TTL_MS, 200);
    // One set of Baileys caches per account, reused by every socket it opens (see TtlCache).
    private readonly retryCounters = new TtlCache(60 * 60_000, 2_000);
    private readonly userDevices = new TtlCache(5 * 60_000, 2_000);
    private readonly callOffers = new TtlCache(5 * 60_000, 100);
    private readonly placeholderResends = new TtlCache(60 * 60_000, 1_000);
    private sweepTimer: NodeJS.Timeout | null;

    constructor(
        private readonly options: AdapterOptions,
        private readonly logger: Logger,
        private readonly callbacks: AdapterCallbacks
    ) {
        this.baileysLog = baileysLogger(options.instanceId);
        this.reads = new ReadBudget(options.readsPerMinute);
        this.sent = new SentMessageStore(join(options.sessionDirectory, 'sent-messages'));
        this.sweepTimer = setInterval(() => void this.sent.sweep(), SWEEP_INTERVAL_MS);
        this.sweepTimer.unref?.();
        void this.sent.sweep();
    }

    getState(): ConnectionStateName {
        return this.state;
    }

    getCurrentQr(): string | null {
        return this.currentQr;
    }

    getUserId(): string | null {
        return this.socket?.user?.id ?? null;
    }

    stats(): Record<string, unknown> {
        return {
            state: this.state,
            recent: this.recent.size,
            lids: this.lids.size,
            knownNumbers: this.knownNumbers.size + this.unknownNumbers.size,
            groups: this.groups.size,
            baileysCaches: {
                retryCounters: this.retryCounters.size,
                userDevices: this.userDevices.size,
                callOffers: this.callOffers.size,
                placeholderResends: this.placeholderResends.size
            },
            readsRemaining: this.reads.remaining,
            protocolNoise: protocolNoise(this.options.instanceId)
        };
    }

    // ------------------------------------------------------------ lifecycle

    async connect(): Promise<void> {
        this.closedByUser = false;
        if (this.socket && this.state !== 'RECONNECTING' && this.state !== 'DISCONNECTED' && this.state !== 'FAILED'
            && this.state !== 'LOGGED_OUT') {
            return;
        }
        this.loginCycles = 0;
        await this.openSocket();
    }

    async disconnect(): Promise<void> {
        this.closedByUser = 'disconnect';
        this.pairing = null;
        this.cancelReconnect();
        // A pending open sees the generation changed and gives up instead of raising a socket for a stopped account.
        this.generation++;
        this.dropSocket();
        this.setState('DISCONNECTED', 'USER_LOGOUT', 'disconnect() requested');
    }

    async logout(): Promise<void> {
        this.closedByUser = 'logout';
        this.pairing = null;
        this.cancelReconnect();
        try {
            await this.socket?.logout();
        } catch (err) {
            this.logger.warn({ err }, 'logout on WhatsApp failed; cleaning up locally');
        }
        this.generation++;
        this.dropSocket();
        await wipeCredentials(this.options.sessionDirectory, this.logger);
        this.setState('LOGGED_OUT', 'USER_LOGOUT', 'logout() requested');
    }

    /** Releases what lives outside the socket; the account is gone for good. */
    destroy(): void {
        if (this.sweepTimer) {
            clearInterval(this.sweepTimer);
            this.sweepTimer = null;
        }
        this.retryCounters.flushAll();
        this.userDevices.flushAll();
        this.callOffers.flushAll();
        this.placeholderResends.flushAll();
        forgetProtocolNoise(this.options.instanceId);
    }

    private openSocket(): Promise<void> {
        // One open at a time: two concurrent opens raised two sockets on one account,
        // and the server's connectionReplaced on the duplicate fed the loop.
        this.opening ??= this.open().finally(() => {
            this.opening = null;
        });
        return this.opening;
    }

    private async open(): Promise<void> {
        this.cancelReconnect();
        this.dropSocket();
        const generation = ++this.generation;
        this.setState('CONNECTING', null, null);
        this.readyForPairing = new Promise<void>((resolve) => {
            this.markReady = resolve;
        });

        if (this.sessionWipe) {
            await this.sessionWipe;
            this.sessionWipe = null;
        }
        const { state, saveCreds, backupCreds } = await useDurableAuthState(this.options.sessionDirectory, this.logger);
        const version = await resolveWhatsAppVersion(this.logger);
        if (this.generation !== generation) {
            return;
        }
        this.backupCreds = backupCreds;

        const socket = makeWASocket({
            auth: state,
            ...(version ? { version } : {}),
            logger: this.baileysLog as never,
            browser: this.options.browser,
            markOnlineOnConnect: this.options.alwaysOnline,
            keepAliveIntervalMs: KEEP_ALIVE_MS,
            connectTimeoutMs: CONNECT_TIMEOUT_MS,
            defaultQueryTimeoutMs: QUERY_TIMEOUT_MS,
            transactionOpts: { maxCommitRetries: 5, delayBetweenTriesMs: 500 },
            // Old messages are never requested. The phone still pushes one bootstrap right after
            // pairing, and Baileys 7 needs it for the LID mappings (without them it warns of session
            // errors); its messages go to messaging-history.set, which nothing here listens to.
            syncFullHistory: false,
            shouldSyncHistoryMessage: (history) => HISTORY_KEPT_FOR_LID_MAPPINGS.has(history.syncType ?? -1),
            // Status updates, channels and broadcast lists are acknowledged and dropped before decryption.
            shouldIgnoreJid: (jid) => isIgnoredAddress(jid),
            emitOwnEvents: false,
            generateHighQualityLinkPreview: false,
            // Retries read our sent messages from disk through getMessage; no second copy in the heap.
            enableRecentMessageCache: false,
            msgRetryCounterCache: this.retryCounters,
            userDevicesCache: this.userDevices,
            callOfferCache: this.callOffers,
            placeholderResendCache: this.placeholderResends,
            cachedGroupMetadata: async (jid) => this.groups.get<GroupMetadata>(jid),
            getMessage: async (key) => this.sent.get(key)
        });

        this.socket = socket;
        this.available = this.options.alwaysOnline;
        this.subscribedPresence.clear();
        this.qrInCycle = 0;
        this.listen(socket, saveCreds, generation);
    }

    private listen(socket: WASocket, saveCreds: () => Promise<void>, generation: number): void {
        const on = <T extends keyof BaileysEventMap>(event: T, handler: (arg: BaileysEventMap[T]) => void): void => {
            const guarded = (arg: BaileysEventMap[T]): void => {
                // A late event from a socket that was already replaced.
                if (this.generation !== generation) return;
                try {
                    handler(arg);
                } catch (err) {
                    this.logger.error({ err, event }, 'handler failed');
                }
            };
            socket.ev.on(event, guarded);
            this.subscriptions.push(() => socket.ev.off(event, guarded));
        };

        on('creds.update', () => {
            // This socket's own writer: during a swap `this` already points at the next one.
            void saveCreds().catch((err) => this.logger.error({ err }, 'failed to write credentials; session kept in memory'));
        });
        on('connection.update', (update) => this.onConnectionUpdate(update));
        on('lid-mapping.update', (mapping) => {
            const pair = mapping as unknown as { lid?: string; pn?: string };
            this.lids.remember(pair.lid, pair.pn);
        });
        on('messages.upsert', ({ messages, type }) => {
            if (type !== 'notify' && type !== 'append') return;
            for (const message of messages) {
                this.onIncoming(message);
            }
        });
        on('messages.update', (updates) => {
            for (const { key, update } of updates) {
                if (!key.remoteJid || !key.id) continue;
                if (update.messageStubType === proto.WebMessageInfo.StubType.REVOKE) {
                    this.callbacks.onMessageDeleted(this.lids.resolve(key.remoteJid), key.id);
                    continue;
                }
                const status = typeof update.status === 'number' ? STATUS_NAMES[update.status] ?? null : null;
                const edited = update.message?.editedMessage?.message ?? update.message?.protocolMessage?.editedMessage;
                const editedText = edited?.conversation ?? edited?.extendedTextMessage?.text ?? null;
                if (!status && !editedText) continue;
                this.callbacks.onMessageUpdated({
                    chatId: this.lids.resolve(key.remoteJid),
                    messageId: key.id,
                    fromMe: key.fromMe ?? false,
                    status,
                    editedText
                });
            }
        });
        on('messages.delete', (payload) => {
            if (!('keys' in payload)) return;
            for (const key of payload.keys) {
                if (key.remoteJid && key.id) {
                    this.callbacks.onMessageDeleted(this.lids.resolve(key.remoteJid), key.id);
                }
            }
        });
        on('messages.reaction', (reactions) => {
            for (const { key, reaction } of reactions) {
                if (!key.remoteJid || !key.id) continue;
                const chatId = this.lids.resolve(key.remoteJid);
                const emoji = reaction.text ?? '';
                this.callbacks.onReaction({
                    chatId,
                    messageId: key.id,
                    senderId: reaction.key?.fromMe ? 'me' : this.lids.resolve(reaction.key?.participant ?? key.remoteJid),
                    emoji: emoji.length > 0 ? emoji : null,
                    removed: emoji.length === 0
                });
            }
        });
        on('groups.update', (updates) => {
            for (const update of updates) {
                if (!update.id) continue;
                this.groups.del(update.id);
                this.callbacks.onGroupUpdated({
                    groupJid: update.id,
                    action: 'update',
                    participants: [],
                    subject: update.subject ?? null,
                    description: update.desc ?? null
                });
            }
        });
        on('group-participants.update', (event) => {
            this.groups.del(event.id);
            this.callbacks.onGroupUpdated({
                groupJid: event.id,
                action: event.action,
                participants: event.participants.map((p) => this.lids.resolve(typeof p === 'string' ? p : String(p))),
                subject: null,
                description: null
            });
        });
        on('presence.update', ({ id, presences }) => {
            const chatId = this.lids.resolve(id);
            for (const [participant, presence] of Object.entries(presences)) {
                this.callbacks.onPresenceUpdated({
                    chatId,
                    participant: this.lids.resolve(participant),
                    presence: presence.lastKnownPresence,
                    lastSeen: presence.lastSeen ?? null
                });
            }
        });
        on('call', (calls) => {
            for (const call of calls) {
                // An offer delivered after a reconnect rang while we were away: there is nothing left to answer.
                if (call.status !== 'offer' || call.offline) continue;
                this.callbacks.onCall({
                    callId: call.id,
                    from: call.from,
                    chatId: this.lids.resolve(call.chatId),
                    isVideo: call.isVideo ?? false,
                    isGroup: call.isGroup ?? false,
                    timestamp: Math.floor(new Date(call.date).getTime() / 1000)
                });
            }
        });
    }

    private onIncoming(message: WAMessage): void {
        this.learnLids(message);
        if (!message.message || !message.key.remoteJid || !message.key.id) return;
        if (timestampOf(message) < this.options.acceptSince) return;
        const type = contentTypeOf(message);
        if (type === 'pollUpdateMessage') {
            void this.onPollVote(message);
            return;
        }
        if (!type || IGNORED_CONTENT.has(type)) return;
        this.recent.remember(message);
        this.callbacks.onMessage(toAbstractMessage(message, this.lids, this.options.includeRawData));
    }

    private dropSocket(): void {
        for (const unsubscribe of this.subscriptions) {
            try {
                unsubscribe();
            } catch {
                // emitter already gone
            }
        }
        this.subscriptions = [];
        const socket = this.socket;
        this.socket = null;
        if (!socket) return;
        try {
            socket.end(undefined);
        } catch {
            // already closed
        }
    }

    private cancelReconnect(): void {
        if (this.reconnectTimer) {
            clearTimeout(this.reconnectTimer);
            this.reconnectTimer = null;
        }
    }

    private onConnectionUpdate(update: { connection?: string; qr?: string; lastDisconnect?: { error?: Error } }): void {
        if (update.qr) {
            this.markReady?.();
            if (this.pairing) {
                // The operator is typing a code; a QR would flip the state and let the code die with this socket.
                this.reregisterPairing();
                return;
            }
            this.qrInCycle++;
            this.currentQr = update.qr;
            this.setState('QR_PENDING', null, null);
            this.callbacks.onQr(update.qr, this.loginCycles + 1);
            return;
        }
        if (update.connection === 'open') {
            this.markReady?.();
            this.currentQr = null;
            this.reconnectAttempts = 0;
            this.immediateReconnects = 0;
            this.loginCycles = 0;
            this.pairing = null;
            this.pairingRegisteredOn = null;
            this.setState('CONNECTED', null, null);
            // A known-good copy for the day creds.json is lost: restarting then resumes instead of asking for a QR.
            void this.backupCreds?.().catch((err) => this.logger.warn({ err }, 'could not back up the credentials'));
            return;
        }
        if (update.connection === 'close') {
            this.onClose(update.lastDisconnect?.error);
        }
    }

    private onClose(error: Error | undefined): void {
        const statusCode = (error as Boom | undefined)?.output?.statusCode;
        const cause: DisconnectCause = (statusCode !== undefined ? CAUSE_BY_STATUS[statusCode] : undefined) ?? 'UNKNOWN';
        const detail = error?.message ?? null;
        const wasLoggingIn = this.state === 'QR_PENDING' || this.state === 'PAIRING_CODE_PENDING';
        this.dropSocket();
        this.currentQr = null;

        if (this.closedByUser) {
            const target = this.closedByUser === 'logout' ? 'LOGGED_OUT' : 'DISCONNECTED';
            this.setState(target, 'USER_LOGOUT', detail ?? `${this.closedByUser}() requested`);
            return;
        }

        if (cause === 'USER_LOGOUT' || cause === 'AUTH_FAILURE') {
            if (statusCode !== undefined && CREDENTIALS_REVOKED.has(statusCode)) {
                this.logger.warn({ statusCode }, 'credentials revoked by WhatsApp; wiping them so the next connect pairs again');
                this.sessionWipe = wipeCredentials(this.options.sessionDirectory, this.logger).catch((err) => {
                    this.logger.error({ err }, 'failed to wipe the revoked session');
                });
            }
            this.pairing = null;
            this.setState(cause === 'USER_LOGOUT' ? 'LOGGED_OUT' : 'FAILED', cause, detail);
            return;
        }

        if (cause === 'CONNECTION_REPLACED') {
            this.logger.warn({ retryInMs: REPLACED_RETRY_MS }, 'another client took this account; credentials kept, retrying later');
            this.setState('RECONNECTING', cause, detail);
            this.scheduleReconnect(false, REPLACED_RETRY_MS);
            return;
        }

        // The QR refs (or the pairing window) ran out: one login cycle is over.
        if (wasLoggingIn && statusCode === DisconnectReason.timedOut) {
            this.loginCycles++;
            this.pairingRegisteredOn = null;
            if (this.loginCycles >= this.options.maxLoginCycles) {
                this.pairing = null;
                this.setState('DISCONNECTED', 'LOGIN_TIMEOUT',
                    `no QR code scan or pairing code typed after ${this.loginCycles} cycles; call connect() again`);
                return;
            }
            this.reconnectAttempts = 0;
            this.setState('RECONNECTING', cause, detail);
            this.scheduleReconnect(true);
            return;
        }

        this.setState('RECONNECTING', cause, detail);
        this.scheduleReconnect(cause === 'WHATSAPP_RESTART');
    }

    private scheduleReconnect(immediate: boolean, fixedDelayMs?: number): void {
        this.cancelReconnect();
        if (this.closedByUser) return;

        const allowImmediate = immediate && this.immediateReconnects < MAX_IMMEDIATE_RECONNECTS;
        this.immediateReconnects = allowImmediate ? this.immediateReconnects + 1 : 0;

        let delay: number;
        if (allowImmediate) {
            delay = 0;
        } else if (fixedDelayMs !== undefined) {
            delay = fixedDelayMs + jitter(1_000);
        } else if (this.reconnectAttempts >= FAILURES_BEFORE_COOLDOWN) {
            delay = COOLDOWN_MS + jitter(5_000);
        } else {
            delay = Math.min(this.options.maxReconnectDelayMs, this.options.minReconnectDelayMs * 2 ** this.reconnectAttempts)
                + jitter(500);
        }
        this.reconnectAttempts++;
        this.logger.info({ delay, attempt: this.reconnectAttempts }, 'reconnect scheduled');

        this.reconnectTimer = setTimeout(() => {
            this.reconnectTimer = null;
            if (this.closedByUser) return;
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
        if (previous !== state || cause === 'LOGIN_TIMEOUT') {
            this.callbacks.onConnectionStateChanged(state, cause, detail);
        }
    }

    // ------------------------------------------------------------ pairing

    /**
     * Pairing code (the alternative to the QR code). The socket has to finish
     * its handshake before the request, and a session that is already paired
     * cannot pair again. The code survives socket swaps: Baileys renews the
     * socket every couple of minutes, and the same code is re-registered on
     * each new one so what the operator is typing stays valid.
     */
    async requestPairingCode(phoneNumber: string, customCode?: string): Promise<string> {
        const phone = phoneNumber.replace(/\D/g, '');
        if (phone.length < 10 || phone.length > 15) {
            throw new BridgeCommandError(ErrorCode.INVALID_PAYLOAD, 'phone number must have 10 to 15 digits, country code included');
        }
        if (customCode !== undefined && !/^[A-Z0-9]{8}$/.test(customCode)) {
            throw new BridgeCommandError(ErrorCode.INVALID_PAYLOAD, 'custom pairing code must be 8 characters, A-Z and 0-9');
        }
        if (!this.socket) {
            await this.connect();
        }
        const socket = this.socket;
        if (!socket) {
            throw new BridgeCommandError(ErrorCode.NOT_CONNECTED, 'connection is not open');
        }
        if (socket.authState.creds.registered) {
            throw new BridgeCommandError(ErrorCode.INVALID_PAYLOAD, 'this session is already paired; log out before pairing another number');
        }
        this.setState('PAIRING_CODE_PENDING', null, null);
        await this.awaitReadyForPairing();
        const current = this.socket;
        if (!current) {
            throw new BridgeCommandError(ErrorCode.NOT_CONNECTED, 'connection closed while waiting for the handshake');
        }
        const code = await current.requestPairingCode(phone, customCode);
        this.pairing = { phone, code };
        this.pairingRegisteredOn = current;
        this.logger.info({ phone: mask(phone) }, 'pairing code issued');
        this.callbacks.onPairingCode(code);
        return code;
    }

    private reregisterPairing(): void {
        const pairing = this.pairing;
        const socket = this.socket;
        if (!pairing || !socket || this.pairingRegisteredOn === socket) return;
        this.pairingRegisteredOn = socket;
        this.setState('PAIRING_CODE_PENDING', null, null);
        socket
            .requestPairingCode(pairing.phone, pairing.code)
            .then(() => this.logger.info({ phone: mask(pairing.phone) }, 'pairing code re-registered on the new socket'))
            .catch((err) => {
                this.pairingRegisteredOn = null;
                this.logger.warn({ err }, 'could not re-register the pairing code; retrying on the next socket');
            });
    }

    private async awaitReadyForPairing(): Promise<void> {
        if (!this.readyForPairing) return;
        await withTimeout(this.readyForPairing, PAIRING_READY_TIMEOUT_MS, 'WhatsApp did not answer the handshake in time');
    }

    // ------------------------------------------------------------ sending

    private requireSocket(): WASocket {
        if (!this.socket || this.state !== 'CONNECTED') {
            throw new BridgeCommandError(ErrorCode.NOT_CONNECTED, `instance ${this.options.instanceId} is not connected`);
        }
        return this.socket;
    }

    /** A message seen in this chat, looked up under both of its addresses. */
    private lookup(chatId: string, messageId: string): WAMessage | undefined {
        return this.recent.message(chatId, messageId) ?? this.recent.message(this.lids.toLid(chatId), messageId);
    }

    private quoted(chatId: string, quotedMessageId: string | undefined): WAMessage | undefined {
        if (!quotedMessageId) return undefined;
        const quoted = this.lookup(chatId, quotedMessageId);
        if (!quoted) {
            throw new BridgeCommandError(ErrorCode.MEDIA_NOT_FOUND, `message ${quotedMessageId} is no longer available to quote`);
        }
        return quoted;
    }

    async send(to: string, content: AnyMessageContent, quotedMessageId?: string): Promise<string> {
        const socket = this.requireSocket();
        const jid = toJid(to);
        const quoted = this.quoted(jid, quotedMessageId);
        try {
            const result = await withTimeout(socket.sendMessage(jid, content, { quoted }), SEND_TIMEOUT_MS, 'send timed out');
            if (!result?.key.id) {
                throw new BridgeCommandError(ErrorCode.SEND_FAILED, 'WhatsApp returned no message id');
            }
            void this.sent.put(result);
            this.recent.touch(result);
            return result.key.id;
        } catch (err) {
            throw asCommandError(err, ErrorCode.SEND_FAILED);
        }
    }

    async sendButtons(to: string, payload: Content.SendButtonsPayload, quotedMessageId?: string): Promise<string> {
        const socket = this.requireSocket();
        const jid = toJid(to);
        const quoted = this.quoted(jid, quotedMessageId);
        const userJid = socket.user?.id;
        if (!userJid) {
            throw new BridgeCommandError(ErrorCode.NOT_CONNECTED, 'account identity not known yet');
        }
        try {
            const message = generateWAMessageFromContent(jid, Content.buttonsContent(payload), { userJid, quoted });
            const id = message.key.id;
            if (!id || !message.message) {
                throw new BridgeCommandError(ErrorCode.SEND_FAILED, 'could not build the buttons message');
            }
            await withTimeout(
                socket.relayMessage(jid, message.message, { messageId: id, additionalNodes: [Content.BUTTONS_NODE] }),
                SEND_TIMEOUT_MS,
                'send timed out'
            );
            void this.sent.put(message);
            return id;
        } catch (err) {
            throw asCommandError(err, ErrorCode.SEND_FAILED);
        }
    }

    async editMessage(target: Content.MessageTargetPayload, text: string): Promise<string> {
        const socket = this.requireSocket();
        try {
            const result = await socket.sendMessage(toJid(target.chatId), Content.editContent(target, text));
            if (result) void this.sent.put(result);
            return result?.key.id ?? target.messageId;
        } catch (err) {
            throw asCommandError(err, ErrorCode.SEND_FAILED);
        }
    }

    async deleteMessage(target: Content.MessageTargetPayload): Promise<void> {
        const socket = this.requireSocket();
        try {
            await socket.sendMessage(toJid(target.chatId), Content.deleteContent(target));
        } catch (err) {
            throw asCommandError(err, ErrorCode.SEND_FAILED);
        }
    }

    async sendReaction(target: Content.MessageTargetPayload, emoji: string): Promise<void> {
        const socket = this.requireSocket();
        try {
            await socket.sendMessage(toJid(target.chatId), Content.reactionContent(target, emoji));
        } catch (err) {
            throw asCommandError(err, ErrorCode.SEND_FAILED);
        }
    }

    /**
     * Publishes our presence. "Typing" from a socket declared unavailable is
     * dropped by WhatsApp without an error, so `available` goes first when the
     * account is not already online; and chat states go to the LID when the
     * chat moved to one, since they are delivered verbatim.
     */
    async sendPresence(chatId: string | undefined, presence: WAPresence): Promise<void> {
        const socket = this.requireSocket();
        try {
            if (presence === 'available' || presence === 'unavailable') {
                await socket.sendPresenceUpdate(presence);
                this.available = presence === 'available';
                return;
            }
            if (!chatId) {
                throw new BridgeCommandError(ErrorCode.INVALID_PAYLOAD, `chatId is required for presence '${presence}'`);
            }
            const target = this.lids.toLid(toJid(chatId));
            if (!this.available) {
                await socket.sendPresenceUpdate('available');
                this.available = true;
            }
            if (!this.subscribedPresence.has(target)) {
                await socket.presenceSubscribe(target);
                this.subscribedPresence.set(target, true);
            }
            await socket.sendPresenceUpdate(presence, target);
        } catch (err) {
            throw asCommandError(err, ErrorCode.SEND_FAILED);
        }
    }

    async subscribePresence(chatId: string): Promise<void> {
        const socket = this.requireSocket();
        const target = this.lids.toLid(toJid(chatId));
        if (this.subscribedPresence.has(target)) return;
        try {
            await socket.presenceSubscribe(target);
            this.subscribedPresence.set(target, true);
        } catch (err) {
            throw asCommandError(err, ErrorCode.SEND_FAILED);
        }
    }

    /**
     * Read receipt (the blue ticks). A receipt needs the full key; it comes
     * from the remembered keys, or is rebuilt from what the caller knows.
     */
    async markRead(chatId: string, messageId: string | undefined, participant: string | undefined): Promise<void> {
        const socket = this.requireSocket();
        const jid = toJid(chatId);
        const lid = this.lids.toLid(jid);
        let targets = [...this.recent.takeUnread(jid, messageId), ...(lid !== jid ? this.recent.takeUnread(lid, messageId) : [])];
        if (targets.length === 0 && messageId) {
            targets = [{ remoteJid: jid, id: messageId, participant }];
        }
        if (targets.length === 0) {
            return;
        }
        try {
            await socket.readMessages(targets.map((t) => ({ remoteJid: t.remoteJid, id: t.id, participant: t.participant, fromMe: false })));
        } catch (err) {
            throw asCommandError(err, ErrorCode.SEND_FAILED);
        }
    }

    async rejectCall(callId: string, from: string): Promise<void> {
        const socket = this.requireSocket();
        try {
            await socket.rejectCall(callId, from);
        } catch (err) {
            throw asCommandError(err, ErrorCode.SEND_FAILED);
        }
    }

    /** Streams the media straight to a file: a video never sits whole in either heap. */
    async downloadMedia(chatId: string, messageId: string, targetPath: string): Promise<{ path: string; mimetype: string; size: number }> {
        const socket = this.requireSocket();
        const message = this.lookup(toJid(chatId), messageId);
        if (!message) {
            throw new BridgeCommandError(ErrorCode.MEDIA_NOT_FOUND, `message ${messageId} is no longer available for download`);
        }
        const temporary = targetPath + '.part';
        try {
            const stream = await downloadMediaMessage(message, 'stream', {}, {
                logger: this.baileysLog as never,
                reuploadRequest: socket.updateMediaMessage
            });
            await pipeline(stream, createWriteStream(temporary));
            await rename(temporary, targetPath);
            const size = (await stat(targetPath)).size;
            return { path: targetPath, mimetype: mimetypeOf(message), size };
        } catch (err) {
            await unlink(temporary).catch(() => undefined);
            throw asCommandError(err, ErrorCode.MEDIA_NOT_FOUND);
        }
    }

    // ------------------------------------------------------------ queries

    async getMe(): Promise<{ jid: string | null; phoneNumber: string | null; name: string | null }> {
        const user = this.requireSocket().user;
        const digits = (user?.id ?? '').split(':')[0]?.split('@')[0]?.replace(/\D/g, '') ?? '';
        return {
            jid: digits ? `${digits}@s.whatsapp.net` : null,
            phoneNumber: digits || null,
            name: user?.name ?? user?.verifiedName ?? null
        };
    }

    /** One query, cached: asking about the same number again is itself a probing pattern. */
    async checkNumber(number: string): Promise<{ jid: string; exists: boolean; cached: boolean }> {
        const digits = number.replace(/\D/g, '');
        const known = this.knownNumbers.get<string>(digits);
        if (known) return { jid: known, exists: true, cached: true };
        if (this.unknownNumbers.get<boolean>(digits)) return { jid: `${digits}@s.whatsapp.net`, exists: false, cached: true };

        const socket = this.requireSocket();
        this.reads.consume('check_number');
        const results = await socket.onWhatsApp(digits);
        const first = results?.[0];
        const exists = first?.exists != null && first.exists !== false;
        // WhatsApp knows whether the account is the one with or without the extra ninth digit.
        const jid = first?.jid ?? `${digits}@s.whatsapp.net`;
        this.lids.remember((first as { lid?: string } | undefined)?.lid, jid);
        if (exists) {
            this.knownNumbers.set(digits, jid);
        } else {
            this.unknownNumbers.set(digits, true);
        }
        return { jid, exists, cached: false };
    }

    /** Three queries (existence, picture, status text): charged three times. Prefer checkNumber or getProfilePicture. */
    async getContact(jid: string): Promise<{ jid: string; status: string | null; profilePictureUrl: string | null; onWhatsApp: boolean }> {
        const socket = this.requireSocket();
        this.reads.consume('get_contact', 3);
        const target = toJid(jid);
        const results = await socket.onWhatsApp(target);
        const exists = results?.[0]?.exists != null && results[0].exists !== false;
        const resolved = results?.[0]?.jid ?? target;
        const picture = await socket.profilePictureUrl(resolved, 'image').catch(() => undefined);
        const statusResult = await socket.fetchStatus(resolved).catch(() => undefined);
        const status = (statusResult?.[0] as unknown as { status?: { status?: string } } | undefined)?.status?.status ?? null;
        return { jid: resolved, status, profilePictureUrl: picture ?? null, onWhatsApp: exists };
    }

    async getProfilePicture(jid: string): Promise<{ jid: string; profilePictureUrl: string | null }> {
        const socket = this.requireSocket();
        this.reads.consume('get_profile_picture');
        const target = toJid(jid);
        const url = await socket.profilePictureUrl(target, 'image').catch(() => undefined);
        return { jid: target, profilePictureUrl: url ?? null };
    }

    async getGroupMetadata(jid: string): Promise<GroupMetadata> {
        const cached = this.groups.get<GroupMetadata>(jid);
        if (cached) return cached;
        const socket = this.requireSocket();
        this.reads.consume('get_group_metadata');
        const metadata = await socket.groupMetadata(jid);
        this.groups.set(jid, metadata);
        return metadata;
    }

    async listGroups(): Promise<GroupMetadata[]> {
        const socket = this.requireSocket();
        // One call, but it returns every group with every participant.
        this.reads.consume('list_groups', 3);
        const groups = Object.values(await socket.groupFetchAllParticipating());
        for (const group of groups) {
            this.groups.set(group.id, group);
        }
        return groups;
    }

    createGroup(subject: string, participants: string[]): Promise<GroupMetadata> {
        return this.requireSocket().groupCreate(subject, participants.map(toJid));
    }

    updateGroupParticipants(groupJid: string, participants: string[], action: 'add' | 'remove' | 'promote' | 'demote') {
        this.groups.del(groupJid);
        return this.requireSocket().groupParticipantsUpdate(groupJid, participants.map(toJid), action);
    }

    updateGroupSubject(groupJid: string, subject: string): Promise<void> {
        this.groups.del(groupJid);
        return this.requireSocket().groupUpdateSubject(groupJid, subject);
    }

    updateGroupDescription(groupJid: string, description: string): Promise<void> {
        this.groups.del(groupJid);
        return this.requireSocket().groupUpdateDescription(groupJid, description);
    }

    leaveGroup(groupJid: string): Promise<void> {
        this.groups.del(groupJid);
        return this.requireSocket().groupLeave(groupJid);
    }

    getGroupInviteCode(groupJid: string): Promise<string | undefined> {
        return this.requireSocket().groupInviteCode(groupJid);
    }

    // ------------------------------------------------------------ contacts, groups, profile, privacy

    /** Several numbers in one query; each uncached number is still charged to the read budget. */
    async checkNumbers(numbers: string[]): Promise<Array<{ number: string; jid: string; exists: boolean; cached: boolean }>> {
        const digits = [...new Set(numbers.map((n) => n.replace(/\D/g, '')).filter((n) => n.length > 0))];
        if (digits.length === 0 || digits.length > 50) {
            throw new BridgeCommandError(ErrorCode.INVALID_PAYLOAD, 'between 1 and 50 numbers per call');
        }
        const answers = new Map<string, { jid: string; exists: boolean; cached: boolean }>();
        const unknown: string[] = [];
        for (const number of digits) {
            const known = this.knownNumbers.get<string>(number);
            if (known) answers.set(number, { jid: known, exists: true, cached: true });
            else if (this.unknownNumbers.get<boolean>(number)) answers.set(number, { jid: `${number}@s.whatsapp.net`, exists: false, cached: true });
            else unknown.push(number);
        }
        if (unknown.length > 0) {
            const socket = this.requireSocket();
            this.reads.consume('check_numbers', unknown.length);
            const results = (await socket.onWhatsApp(...unknown)) ?? [];
            for (const number of unknown) {
                const found = results.find((r) => (r.jid ?? '').startsWith(number) && r.exists);
                if (found?.jid) {
                    this.knownNumbers.set(number, found.jid);
                    this.lids.remember((found as { lid?: string }).lid, found.jid);
                    answers.set(number, { jid: found.jid, exists: true, cached: false });
                } else {
                    this.unknownNumbers.set(number, true);
                    answers.set(number, { jid: `${number}@s.whatsapp.net`, exists: false, cached: false });
                }
            }
        }
        return digits.map((number) => ({ number, ...answers.get(number)! }));
    }

    /** Whether a group exists and whether this account is in it, from one metadata query. */
    async groupExists(groupJid: string): Promise<{ groupJid: string; exists: boolean; isMember: boolean; subject: string | null }> {
        try {
            const metadata = await this.getGroupMetadata(groupJid);
            const me = this.ownIds();
            const isMember = metadata.participants.some((p) => me.has(jidNormalizedUser(p.id)) || (p.lid !== undefined && me.has(jidNormalizedUser(p.lid))));
            return { groupJid, exists: true, isMember, subject: metadata.subject ?? null };
        } catch (err) {
            const status = (err as Boom).output?.statusCode;
            if (status === 404 || status === 406) return { groupJid, exists: false, isMember: false, subject: null };
            if (status === 401 || status === 403) return { groupJid, exists: true, isMember: false, subject: null };
            throw err;
        }
    }

    async getInviteInfo(code: string): Promise<GroupMetadata> {
        const socket = this.requireSocket();
        this.reads.consume('get_invite_info');
        return socket.groupGetInviteInfo(inviteCodeOf(code));
    }

    async joinGroup(code: string): Promise<string | null> {
        return (await this.requireSocket().groupAcceptInvite(inviteCodeOf(code))) ?? null;
    }

    async revokeInviteCode(groupJid: string): Promise<string | null> {
        return (await this.requireSocket().groupRevokeInvite(groupJid)) ?? null;
    }

    async setGroupSetting(groupJid: string, setting: 'announcement' | 'not_announcement' | 'locked' | 'unlocked'): Promise<void> {
        this.groups.del(groupJid);
        await this.requireSocket().groupSettingUpdate(groupJid, setting);
    }

    /** Disappearing messages: 0 turns them off; WhatsApp accepts 86400, 604800 and 7776000 seconds. */
    async setDisappearing(chatId: string, seconds: number): Promise<void> {
        const socket = this.requireSocket();
        const jid = toJid(chatId);
        if (jid.endsWith('@g.us')) {
            await socket.groupToggleEphemeral(jid, seconds);
        } else {
            await socket.sendMessage(jid, { disappearingMessagesInChat: seconds > 0 ? seconds : false });
        }
    }

    async updateProfilePicture(jid: string | undefined, filePath: string): Promise<void> {
        const socket = this.requireSocket();
        await socket.updateProfilePicture(jid ? toJid(jid) : jidNormalizedUser(socket.user!.id), { url: filePath });
    }

    async removeProfilePicture(jid: string | undefined): Promise<void> {
        const socket = this.requireSocket();
        await socket.removeProfilePicture(jid ? toJid(jid) : jidNormalizedUser(socket.user!.id));
    }

    async updateProfileName(name: string): Promise<void> {
        await this.requireSocket().updateProfileName(name);
    }

    async updateProfileStatus(status: string): Promise<void> {
        await this.requireSocket().updateProfileStatus(status);
    }

    async setBlocked(jid: string, blocked: boolean): Promise<void> {
        await this.requireSocket().updateBlockStatus(toJid(jid), blocked ? 'block' : 'unblock');
    }

    async getBlocklist(): Promise<string[]> {
        const socket = this.requireSocket();
        this.reads.consume('get_blocklist');
        return ((await socket.fetchBlocklist()) ?? []).filter((jid): jid is string => typeof jid === 'string').map((jid) => this.lids.resolve(jid));
    }

    async getStatus(jid: string): Promise<{ jid: string; status: string | null; setAt: number | null }> {
        const socket = this.requireSocket();
        this.reads.consume('get_status');
        const target = toJid(jid);
        const result = (await socket.fetchStatus(target))?.[0] as { status?: { status?: string; setAt?: Date } } | undefined;
        const setAt = result?.status?.setAt;
        return { jid: target, status: result?.status?.status ?? null, setAt: setAt ? Math.floor(new Date(setAt).getTime() / 1000) : null };
    }

    async getBusinessProfile(jid: string): Promise<Record<string, unknown> | null> {
        const socket = this.requireSocket();
        this.reads.consume('get_business_profile');
        const profile = await socket.getBusinessProfile(toJid(jid));
        return (profile as Record<string, unknown> | undefined) ?? null;
    }

    async getPrivacySettings(): Promise<Record<string, string>> {
        const socket = this.requireSocket();
        this.reads.consume('get_privacy_settings');
        return (await socket.fetchPrivacySettings(true)) as Record<string, string>;
    }

    async updatePrivacy(setting: PrivacySetting, value: string): Promise<void> {
        const allowed = PRIVACY_VALUES[setting];
        if (!allowed || !allowed.has(value)) {
            throw new BridgeCommandError(ErrorCode.INVALID_PAYLOAD, `invalid value '${value}' for privacy setting '${setting}'`);
        }
        const socket = this.requireSocket();
        const v = value as never;
        switch (setting) {
            case 'last_seen':
                return socket.updateLastSeenPrivacy(v);
            case 'online':
                return socket.updateOnlinePrivacy(v);
            case 'profile_picture':
                return socket.updateProfilePicturePrivacy(v);
            case 'status':
                return socket.updateStatusPrivacy(v);
            case 'read_receipts':
                return socket.updateReadReceiptsPrivacy(v);
            case 'groups_add':
                return socket.updateGroupsAddPrivacy(v);
        }
    }

    /** Archive and unread need the newest message of the chat as their anchor, as the phone does. */
    async modifyChat(chatId: string, change: ChatChange, muteUntilMs?: number): Promise<void> {
        const socket = this.requireSocket();
        const jid = toJid(chatId);
        const anchor = (): Array<{ key: proto.IMessageKey; messageTimestamp: number }> => {
            const last = this.recent.lastMessage(jid) ?? this.recent.lastMessage(this.lids.toLid(jid));
            if (!last) {
                throw new BridgeCommandError(ErrorCode.MEDIA_NOT_FOUND, `no recent message in ${chatId} to anchor '${change}'`);
            }
            return [last];
        };
        switch (change) {
            case 'mute':
                return socket.chatModify({ mute: muteUntilMs ?? -1 }, jid);
            case 'unmute':
                return socket.chatModify({ mute: null }, jid);
            case 'pin':
                return socket.chatModify({ pin: true }, jid);
            case 'unpin':
                return socket.chatModify({ pin: false }, jid);
            case 'archive':
                return socket.chatModify({ archive: true, lastMessages: anchor() }, jid);
            case 'unarchive':
                return socket.chatModify({ archive: false, lastMessages: anchor() }, jid);
            case 'mark_unread':
                return socket.chatModify({ markRead: false, lastMessages: anchor() }, jid);
        }
    }

    async starMessage(target: Content.MessageTargetPayload, star: boolean): Promise<void> {
        await this.requireSocket().chatModify(
            { star: { messages: [{ id: target.messageId, fromMe: target.fromMe }], star } },
            toJid(target.chatId)
        );
    }

    /** Forwards a message this bridge still has: one received recently, or one this account sent. */
    async forwardMessage(chatId: string, messageId: string, to: string): Promise<string> {
        const jid = toJid(chatId);
        const message = this.lookup(jid, messageId) ?? (await this.sent.full(jid, messageId)) ?? (await this.sent.full(this.lids.toLid(jid), messageId));
        if (!message) {
            throw new BridgeCommandError(ErrorCode.MEDIA_NOT_FOUND, `message ${messageId} is no longer available to forward`);
        }
        return this.send(to, { forward: message });
    }

    async sendPoll(to: string, name: string, options: string[], selectableCount: number): Promise<string> {
        const values = [...new Set(options.map((o) => o.trim()).filter((o) => o.length > 0))];
        if (!name.trim() || values.length < 2 || values.length > 12) {
            throw new BridgeCommandError(ErrorCode.INVALID_PAYLOAD, 'a poll needs a question and 2 to 12 distinct options');
        }
        const count = Math.max(0, Math.min(selectableCount, values.length));
        return this.send(to, { poll: { name, values, selectableCount: count } });
    }

    /**
     * Baileys leaves poll votes encrypted (its decryption is disabled upstream). A vote is
     * decrypted here with the secret of our own poll, read back from the sent-message store, and
     * reported with the option names instead of their hashes.
     */
    private async onPollVote(message: WAMessage): Promise<void> {
        const update = normalizeMessageContent(message.message)?.pollUpdateMessage;
        const creationKey = update?.pollCreationMessageKey;
        if (!update?.vote || !creationKey?.id) return;
        const chat = creationKey.remoteJid ?? message.key.remoteJid ?? '';
        const poll = (await this.sent.full(chat, creationKey.id))
            ?? (await this.sent.full(this.lids.toLid(chat), creationKey.id))
            ?? (await this.sent.full(this.lids.resolve(chat), creationKey.id));
        if (!poll) return;
        const content = normalizeMessageContent(poll.message);
        const secret = content?.messageContextInfo?.messageSecret ?? poll.message?.messageContextInfo?.messageSecret;
        const creation = content?.pollCreationMessage ?? content?.pollCreationMessageV2 ?? content?.pollCreationMessageV3;
        if (!secret || !creation?.options) return;

        const key = message.key as WAMessage['key'] & { participantAlt?: string; remoteJidAlt?: string };
        const voters = unique([key.participant, key.participantAlt, key.remoteJid, key.remoteJidAlt]);
        let vote: proto.Message.PollVoteMessage | null = null;
        for (const creator of this.ownIds()) {
            for (const voter of voters) {
                try {
                    vote = decryptPollVote(update.vote, { pollEncKey: secret, pollCreatorJid: creator, pollMsgId: creationKey.id, voterJid: voter });
                    break;
                } catch {
                    // wrong identity pair: try the next address form
                }
            }
            if (vote) break;
        }
        if (!vote) {
            this.logger.debug({ pollId: creationKey.id }, 'could not decrypt a poll vote');
            return;
        }
        const byHash = new Map(creation.options.map((o) => [createHash('sha256').update(o.optionName ?? '').digest('hex'), o.optionName ?? '']));
        const selected = (vote.selectedOptions ?? []).map((h) => byHash.get(Buffer.from(h).toString('hex'))).filter((o): o is string => o !== undefined);
        this.callbacks.onPollVote({
            chatId: this.lids.resolve(chat),
            pollMessageId: creationKey.id,
            voterId: this.lids.resolve(voters[0] ?? ''),
            selectedOptions: selected,
            timestamp: timestampOf(message)
        });
    }

    /** Every address this account answers to (phone number and LID), normalised. */
    private ownIds(): Set<string> {
        const user = this.socket?.user as { id?: string; lid?: string } | undefined;
        return new Set(unique([user?.id, user?.lid]).map((jid) => jidNormalizedUser(jid)));
    }

    private learnLids(message: WAMessage): void {
        // v7 carries the other address form in remoteJidAlt/participantAlt; older stanzas in senderPn/participantPn.
        const key = message.key as {
            remoteJid?: string | null;
            remoteJidAlt?: string;
            participant?: string | null;
            participantAlt?: string;
            senderPn?: string;
            participantPn?: string;
        };
        this.lids.remember(key.remoteJid, key.remoteJidAlt ?? key.senderPn);
        this.lids.remember(key.participant, key.participantAlt ?? key.participantPn);
    }
}

function unique(values: Array<string | null | undefined>): string[] {
    return [...new Set(values.filter((v): v is string => typeof v === 'string' && v.length > 0))];
}

/** Accepts the bare code or the whole chat.whatsapp.com link. */
function inviteCodeOf(value: string): string {
    const code = value.trim().replace(/^https?:\/\/chat\.whatsapp\.com\//i, '').split(/[/?#]/)[0] ?? '';
    if (!/^[A-Za-z0-9]{10,40}$/.test(code)) {
        throw new BridgeCommandError(ErrorCode.INVALID_PAYLOAD, 'not a group invite code or link');
    }
    return code;
}

const STATUS_NAMES: Record<number, string> = {
    0: 'ERROR',
    1: 'PENDING',
    2: 'SERVER_ACK',
    3: 'DELIVERED',
    4: 'READ',
    5: 'PLAYED'
};

/** Status updates, channels and broadcast lists: content the application never uses. */
function isIgnoredAddress(jid: string | undefined | null): boolean {
    if (!jid) return false;
    const server = jid.slice(jid.indexOf('@') + 1);
    return server === 'broadcast' || server === 'newsletter';
}

/** Accepts a bare phone number as well as a JID. */
export function toJid(value: string): string {
    if (value.includes('@')) return value;
    const digits = value.replace(/\D/g, '');
    if (digits.length === 0) {
        throw new BridgeCommandError(ErrorCode.INVALID_PAYLOAD, `not a phone number or JID: ${value}`);
    }
    return `${digits}@s.whatsapp.net`;
}

function timestampOf(message: WAMessage): number {
    const raw = message.messageTimestamp;
    if (typeof raw === 'number') return raw;
    return raw ? Number(raw.toString()) : Math.floor(Date.now() / 1000);
}

function mimetypeOf(message: WAMessage): string {
    const content = message.message;
    return (
        content?.imageMessage?.mimetype ??
        content?.videoMessage?.mimetype ??
        content?.audioMessage?.mimetype ??
        content?.documentMessage?.mimetype ??
        content?.stickerMessage?.mimetype ??
        'application/octet-stream'
    );
}

function jitter(maxMs: number): number {
    return Math.floor(Math.random() * maxMs);
}

/** Masks a phone number down to its last four digits: logs end up in aggregators. */
function mask(phone: string): string {
    return phone.length <= 4 ? '*'.repeat(phone.length) : '*'.repeat(phone.length - 4) + phone.slice(-4);
}

function asCommandError(err: unknown, code: (typeof ErrorCode)[keyof typeof ErrorCode]): BridgeCommandError {
    return err instanceof BridgeCommandError ? err : new BridgeCommandError(code, (err as Error)?.message ?? String(err));
}

export async function withTimeout<T>(promise: Promise<T>, ms: number, message: string): Promise<T> {
    let timer: NodeJS.Timeout | undefined;
    const expiry = new Promise<never>((_, reject) => {
        timer = setTimeout(() => reject(new BridgeCommandError(ErrorCode.TIMEOUT, message)), ms);
        timer.unref?.();
    });
    try {
        return await Promise.race([promise, expiry]);
    } finally {
        if (timer) clearTimeout(timer);
    }
}
