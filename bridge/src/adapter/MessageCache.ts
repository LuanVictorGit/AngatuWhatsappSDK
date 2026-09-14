import { createHash } from 'node:crypto';
import { mkdirSync, readdirSync, readFileSync, renameSync, statSync, unlinkSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { proto, type WAMessage } from 'baileys';

interface CacheEntry {
    message: WAMessage;
    cachedAt: number;
}

/**
 * Por quanto tempo uma mensagem que NOS enviamos fica guardada em disco para
 * atender um pedido de reenvio.
 *
 * Doze horas: o aparelho do destinatario pode estar desligado por uma noite
 * inteira e pedir o reenvio so na manha seguinte.
 */
const TTL_DISCO_MS = 12 * 60 * 60 * 1000;

/** Teto de arquivos guardados, para o diretorio nao crescer sem limite. */
const MAX_ARQUIVOS_DISCO = 3_000;

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
    /** Onde as mensagens enviadas por nos ficam, para sobreviver a um restart. */
    private readonly diretorioEnviadas: string | null;

    /**
     * @param maxEntries teto do cache em memoria
     * @param ttlMs validade de uma entrada em memoria
     * @param diretorioEnviadas pasta onde persistir o que NOS enviamos, ou
     *        {@code null} para nao persistir nada
     */
    constructor(maxEntries = 2000, ttlMs = 60 * 60 * 1000, diretorioEnviadas: string | null = null) {
        this.maxEntries = maxEntries;
        this.ttlMs = ttlMs;
        this.diretorioEnviadas = diretorioEnviadas;
        if (diretorioEnviadas) {
            try {
                mkdirSync(diretorioEnviadas, { recursive: true });
                this.faxinaDoDisco();
            } catch {
                // Sem disco, o cache em memoria continua valendo: perde-se a
                // sobrevivencia ao restart, nao a operacao.
            }
        }
    }

    private key(chatId: string, messageId: string): string {
        return `${chatId}_${messageId}`;
    }

    put(message: WAMessage): void {
        const chatId = message.key.remoteJid;
        const messageId = message.key.id;
        if (!chatId || !messageId) return;

        this.entries.set(this.key(chatId, messageId), { message, cachedAt: Date.now() });
        if (message.key.fromMe) {
            // So o que NOS enviamos vai para o disco: e o unico que um retry
            // receipt pode pedir de volta.
            this.gravarNoDisco(message, chatId, messageId);
        }
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
        if (entry) {
            if (Date.now() - entry.cachedAt <= this.ttlMs) {
                return entry.message;
            }
            this.entries.delete(this.key(chatId, messageId));
        }
        return this.lerDoDisco(chatId, messageId);
    }

    /**
     * Guarda em disco uma mensagem que NOS enviamos.
     *
     * <p><b>Por que so as nossas, e por que em disco.</b> Quando o aparelho do
     * destinatario nao consegue decifrar algo que mandamos, ele pede o reenvio
     * (retry receipt) e o Baileys chama `getMessage` para recifrar a NOSSA
     * copia. Guardado so em memoria, todo pedido referente a uma mensagem
     * anterior ao ultimo restart devolvia `undefined`: o Baileys nao tinha o que
     * recifrar, o outro lado continuava pedindo, e pedido de reenvio repetido e
     * exatamente o sinal que degrada a reputacao do numero.
     *
     * <p>Mensagem recebida nao precisa disto — ninguem vai nos pedir para
     * reenviar o que nao foi nosso —, e persistir tudo poria I/O no caminho de
     * cada mensagem que chega.
     */
    private gravarNoDisco(message: WAMessage, chatId: string, messageId: string): void {
        if (!this.diretorioEnviadas) return;
        try {
            const caminho = join(this.diretorioEnviadas, this.nomeDeArquivo(chatId, messageId));
            const temporario = caminho + '.tmp';
            writeFileSync(temporario, JSON.stringify(proto.WebMessageInfo.toObject(
                proto.WebMessageInfo.fromObject(message as never)
            )));
            renameSync(temporario, caminho);
        } catch {
            // Nunca pode derrubar um envio: no pior caso, um pedido de reenvio
            // futuro fica sem resposta.
        }
    }

    private lerDoDisco(chatId: string, messageId: string): WAMessage | undefined {
        if (!this.diretorioEnviadas) return undefined;
        try {
            const caminho = join(this.diretorioEnviadas, this.nomeDeArquivo(chatId, messageId));
            const bruto = readFileSync(caminho, 'utf8');
            return proto.WebMessageInfo.fromObject(JSON.parse(bruto)) as unknown as WAMessage;
        } catch {
            return undefined;
        }
    }

    /** Nome curto e seguro: o jid tem ':' e '/', que nao valem em todo sistema. */
    private nomeDeArquivo(chatId: string, messageId: string): string {
        return createHash('sha256').update(this.key(chatId, messageId)).digest('hex').slice(0, 32) + '.json';
    }

    /** Apaga o que venceu e, se ainda houver excesso, o mais antigo. */
    private faxinaDoDisco(): void {
        if (!this.diretorioEnviadas) return;
        try {
            const agora = Date.now();
            const arquivos = readdirSync(this.diretorioEnviadas)
                .map((nome) => {
                    const caminho = join(this.diretorioEnviadas!, nome);
                    try {
                        return { caminho, nome, mtime: statSync(caminho).mtimeMs };
                    } catch {
                        return null;
                    }
                })
                .filter((f): f is { caminho: string; nome: string; mtime: number } => f !== null);

            const sobreviventes: typeof arquivos = [];
            for (const f of arquivos) {
                if (agora - f.mtime > TTL_DISCO_MS || f.nome.endsWith('.tmp')) {
                    try {
                        unlinkSync(f.caminho);
                    } catch {
                        // arquivo ja removido por outra passada
                    }
                } else {
                    sobreviventes.push(f);
                }
            }

            if (sobreviventes.length > MAX_ARQUIVOS_DISCO) {
                sobreviventes.sort((a, b) => a.mtime - b.mtime);
                for (const f of sobreviventes.slice(0, sobreviventes.length - MAX_ARQUIVOS_DISCO)) {
                    try {
                        unlinkSync(f.caminho);
                    } catch {
                        // idem
                    }
                }
            }
        } catch {
            // diretorio inacessivel: segue sem persistencia
        }
    }

    /**
     * Roda a faxina do disco. Chamada periodicamente pelo adapter — antes disto,
     * {@code sweepExpired} era codigo morto e nada nunca era descartado.
     */
    manutencao(): void {
        this.sweepExpired();
        this.faxinaDoDisco();
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
