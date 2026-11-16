import { createHash } from 'node:crypto';
import { mkdirSync } from 'node:fs';
import { readdir, readFile, rename, stat, unlink, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { BufferJSON, proto, type WAMessage, type WAMessageKey } from 'baileys';
import { BoundedMap, TtlCache } from '../util/TtlCache.js';

/** Recent incoming messages kept to quote or download them. Small on purpose. */
const RECENT_MESSAGES = 300;
const RECENT_TTL_MS = 30 * 60_000;
/** Keys only (a few strings each): enough to mark a message read hours later. */
const RECENT_KEYS = 5_000;
/** Unread messages remembered per chat; a person rarely leaves more unanswered in one chat. */
const UNREAD_PER_CHAT = 50;

/** How long a message we sent stays on disk to answer a retry receipt. */
const SENT_TTL_MS = 12 * 60 * 60_000;
const SENT_MAX_FILES = 3_000;

/**
 * Content fields that are pure weight once a message has been received: the
 * embedded JPEG previews and media sidecars. A quote or a media download does
 * not need any of them, and a thumbnail alone can be larger than the rest of
 * the message.
 */
const HEAVY_FIELDS = [
    'jpegThumbnail',
    'thumbnailDirectPath',
    'thumbnailSha256',
    'thumbnailEncSha256',
    'scansSidecar',
    'scanLengths',
    'midQualityFileSha256',
    'streamingSidecar',
    'waveform',
    'pngThumbnail'
];

export interface ReadTarget {
    remoteJid: string;
    id: string;
    participant?: string;
}

/** The newest message of a chat, which WhatsApp wants as the anchor of archive and unread changes. */
export interface LastMessage {
    key: WAMessageKey;
    messageTimestamp: number;
}

/**
 * What the bridge remembers about received messages: a short window of
 * stripped messages (quote, download) and a longer index of bare keys (read
 * receipts). Nothing here is ever fetched back from WhatsApp - history is not
 * read, only what arrives live is kept, briefly.
 */
export class RecentMessages {
    private readonly messages = new TtlCache(RECENT_TTL_MS, RECENT_MESSAGES);
    private readonly keys = new BoundedMap<string, ReadTarget>(RECENT_KEYS);
    private readonly unread = new BoundedMap<string, ReadTarget[]>(RECENT_KEYS);
    private readonly lastInChat = new BoundedMap<string, LastMessage>(RECENT_KEYS);

    remember(message: WAMessage): void {
        const chatId = message.key.remoteJid;
        const id = message.key.id;
        if (!chatId || !id) return;
        const target: ReadTarget = { remoteJid: chatId, id, participant: message.key.participant ?? undefined };
        this.keys.set(keyOf(chatId, id), target);
        if (!message.key.fromMe) {
            const pending = this.unread.get(chatId) ?? [];
            pending.push(target);
            if (pending.length > UNREAD_PER_CHAT) pending.shift();
            this.unread.set(chatId, pending);
        }
        this.messages.set(keyOf(chatId, id), strip(message));
        this.touch(message);
    }

    /** Records a message as the newest of its chat, sent or received. */
    touch(message: WAMessage): void {
        const chatId = message.key.remoteJid;
        if (!chatId || !message.key.id) return;
        const raw = message.messageTimestamp;
        const timestamp = typeof raw === 'number' ? raw : raw ? Number(raw.toString()) : Math.floor(Date.now() / 1000);
        this.lastInChat.set(chatId, { key: message.key, messageTimestamp: timestamp });
    }

    lastMessage(chatId: string): LastMessage | undefined {
        return this.lastInChat.get(chatId);
    }

    message(chatId: string, id: string): WAMessage | undefined {
        return this.messages.get<WAMessage>(keyOf(chatId, id));
    }

    /**
     * What a read receipt should cover: one message, or every unread one of the chat, the way a
     * phone marks a whole conversation read when it is opened. Taken targets leave the unread list.
     */
    takeUnread(chatId: string, id?: string): ReadTarget[] {
        const pending = this.unread.get(chatId) ?? [];
        if (!id) {
            this.unread.delete(chatId);
            return pending;
        }
        const index = pending.findIndex((t) => t.id === id);
        // Reading a message also reads everything that arrived before it.
        const taken = index >= 0 ? pending.splice(0, index + 1) : [];
        if (pending.length === 0) this.unread.delete(chatId);
        if (taken.length > 0) return taken;
        const known = this.keys.get(keyOf(chatId, id));
        return known ? [known] : [];
    }

    get size(): { messages: number; keys: number } {
        return { messages: this.messages.size, keys: this.keys.size };
    }
}

/**
 * Messages this account sent, on disk only. When a recipient's device cannot
 * decrypt one it asks for a resend and Baileys calls `getMessage` to re-encrypt
 * our copy; without it the request goes unanswered, the recipient keeps asking
 * and the session degrades. Disk instead of heap: the copy is read back rarely,
 * and it has to survive a restart anyway.
 */
export class SentMessageStore {
    private ready = false;

    constructor(private readonly directory: string) {
        try {
            mkdirSync(directory, { recursive: true });
            this.ready = true;
        } catch {
            // no disk: retry receipts for earlier messages go unanswered, sending still works
        }
    }

    async put(message: WAMessage): Promise<void> {
        const chatId = message.key.remoteJid;
        const id = message.key.id;
        if (!this.ready || !chatId || !id) return;
        try {
            const path = this.pathOf(chatId, id);
            const temporary = path + '.tmp';
            // BufferJSON keeps byte fields (media keys, the poll secret) intact; plain JSON of a
            // Uint8Array produced an object proto.fromObject silently read back as empty bytes.
            await writeFile(temporary, JSON.stringify(strip(message), BufferJSON.replacer));
            await rename(temporary, path);
        } catch {
            // never allowed to fail a send
        }
    }

    async get(key: WAMessageKey): Promise<proto.IMessage | undefined> {
        return (await this.full(key.remoteJid, key.id))?.message ?? undefined;
    }

    async full(chatId: string | null | undefined, id: string | null | undefined): Promise<WAMessage | undefined> {
        if (!this.ready || !chatId || !id) return undefined;
        try {
            const raw = await readFile(this.pathOf(chatId, id), 'utf8');
            return proto.WebMessageInfo.fromObject(JSON.parse(raw, BufferJSON.reviver)) as WAMessage;
        } catch {
            return undefined;
        }
    }

    /** Deletes what expired and, past the cap, the oldest files. */
    async sweep(): Promise<void> {
        if (!this.ready) return;
        try {
            const now = Date.now();
            const alive: { path: string; mtime: number }[] = [];
            for (const name of await readdir(this.directory)) {
                const path = join(this.directory, name);
                const info = await stat(path).catch(() => null);
                if (!info) continue;
                if (now - info.mtimeMs > SENT_TTL_MS || name.endsWith('.tmp')) {
                    await unlink(path).catch(() => undefined);
                } else {
                    alive.push({ path, mtime: info.mtimeMs });
                }
            }
            if (alive.length > SENT_MAX_FILES) {
                alive.sort((a, b) => a.mtime - b.mtime);
                for (const file of alive.slice(0, alive.length - SENT_MAX_FILES)) {
                    await unlink(file.path).catch(() => undefined);
                }
            }
        } catch {
            // unreadable directory: keep going without persistence
        }
    }

    private pathOf(chatId: string, id: string): string {
        // a JID carries ':' and '/', which are not valid in every file system
        return join(this.directory, createHash('sha256').update(keyOf(chatId, id)).digest('hex').slice(0, 32) + '.json');
    }
}

function keyOf(chatId: string, id: string): string {
    return chatId + '\u0001' + id;
}

/** Shallow copy of the message without the heavy fields of its content. */
export function strip(message: WAMessage): WAMessage {
    const content = message.message;
    if (!content) return message;
    const lighter: Record<string, unknown> = {};
    for (const [type, value] of Object.entries(content)) {
        if (value && typeof value === 'object' && !Array.isArray(value)) {
            const copy: Record<string, unknown> = { ...(value as Record<string, unknown>) };
            for (const field of HEAVY_FIELDS) {
                delete copy[field];
            }
            const context = copy.contextInfo as Record<string, unknown> | undefined;
            if (context?.quotedMessage) {
                copy.contextInfo = { ...context, quotedMessage: undefined };
            }
            lighter[type] = copy;
        } else {
            lighter[type] = value;
        }
    }
    return { ...message, message: lighter as proto.IMessage };
}
