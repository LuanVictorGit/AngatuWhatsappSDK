import type { WAMessage } from 'baileys';

interface CacheEntry {
    message: WAMessage;
    cachedAt: number;
}

/**
 * Baileys has no "fetch message by id" API - it is an end-to-end encrypted
 * realtime protocol, not a REST backend with history storage. Quoting a
 * message (`options.quoted`) and downloading its media both require the
 * original in-memory WAMessage object, so we keep a short bounded cache of
 * recently seen messages per instance. This is a real, documented
 * limitation (see README "Limitacoes"), not a shortcut: quoting/downloading
 * only works for messages observed during the current process lifetime and
 * still within the window below.
 */
export class MessageCache {
    private readonly entries = new Map<string, CacheEntry>();
    /**
     * Ultima mensagem RECEBIDA de cada conversa.
     *
     * Existe para o `markRead(chatId)` sem id: marcar uma conversa como lida
     * exige a chave completa da mensagem (`remoteJid`, `id`, `participant`,
     * `fromMe`), e quem chama normalmente so tem a conversa. Guarda apenas o id
     * — a mensagem em si continua vindo de `entries`, com o mesmo prazo de
     * validade.
     */
    private readonly ultimaRecebida = new Map<string, string>();
    private readonly maxEntries: number;
    private readonly ttlMs: number;

    constructor(maxEntries = 2000, ttlMs = 60 * 60 * 1000) {
        this.maxEntries = maxEntries;
        this.ttlMs = ttlMs;
    }

    private key(chatId: string, messageId: string): string {
        return `${chatId}_${messageId}`;
    }

    put(message: WAMessage): void {
        const chatId = message.key.remoteJid;
        const messageId = message.key.id;
        if (!chatId || !messageId) return;

        this.entries.set(this.key(chatId, messageId), { message, cachedAt: Date.now() });
        if (!message.key.fromMe) {
            this.ultimaRecebida.set(chatId, messageId);
            // Teto proprio: o mapa e uma entrada por CONVERSA, nao por mensagem,
            // mas uma campanha fala com muita gente e ele nao pode crescer sem
            // limite num processo longo.
            if (this.ultimaRecebida.size > this.maxEntries) {
                const maisAntiga = this.ultimaRecebida.keys().next().value;
                if (maisAntiga !== undefined) {
                    this.ultimaRecebida.delete(maisAntiga);
                }
            }
        }
        if (this.entries.size > this.maxEntries) {
            const oldestKey = this.entries.keys().next().value;
            if (oldestKey !== undefined) {
                this.entries.delete(oldestKey);
            }
        }
    }

    /** Id da ultima mensagem recebida numa conversa, se ainda estiver em cache. */
    lastIncomingId(chatId: string): string | undefined {
        return this.ultimaRecebida.get(chatId);
    }

    get(chatId: string, messageId: string): WAMessage | undefined {
        const entry = this.entries.get(this.key(chatId, messageId));
        if (!entry) return undefined;
        if (Date.now() - entry.cachedAt > this.ttlMs) {
            this.entries.delete(this.key(chatId, messageId));
            return undefined;
        }
        return entry.message;
    }

    sweepExpired(): void {
        const now = Date.now();
        for (const [key, entry] of this.entries) {
            if (now - entry.cachedAt > this.ttlMs) {
                this.entries.delete(key);
            }
        }
    }

    get size(): number {
        return this.entries.size;
    }
}
