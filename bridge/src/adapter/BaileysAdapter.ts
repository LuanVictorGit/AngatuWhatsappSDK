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
    type WAPresence,
    type BaileysEventMap
} from 'baileys';
import { Boom } from '@hapi/boom';
import { loggerDoBaileys, type Logger } from '../util/logger.js';
import { MessageCache } from './MessageCache.js';
import { LidDirectory } from './LidDirectory.js';
import { resolverVersaoWhatsApp } from './WaVersion.js';
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
/**
 * Quanto esperar o handshake do WhatsApp antes de pedir o codigo de
 * pareamento. Generoso de proposito: quem esta conectando um numero novo
 * prefere esperar 20s a receber "erro" e nao saber o motivo.
 */
const PAIRING_READY_TIMEOUT_MS = 20_000;

/**
 * Enderecos cujo conteudo o painel nunca usa e por isso nem chega a ser
 * decifrado.
 *
 * `shouldIgnoreJid` e avaliado pelo Baileys no `handleMessage`, ANTES de abrir
 * o envelope: o stanza e reconhecido, respondido com ack e descartado. A
 * diferenca em relacao a filtrar depois (o que o painel ja fazia em
 * `WaEngineSdk.descartavel`) e o que interessa aqui:
 *
 * - `status@broadcast` e o "Recado" de TODOS os contatos da agenda. Sem este
 *   filtro, a aplicacao recebia, decifrava e guardava em cache cada foto,
 *   video e texto que qualquer contato publicasse — dado pessoal de terceiros
 *   que o painel nunca exibiu e nunca teve motivo para ter;
 * - `@newsletter` sao os Canais que a conta segue, igualmente irrelevantes;
 * - `@broadcast` (listas de transmissao) idem.
 *
 * Grupos (`@g.us`) e conversas (`@s.whatsapp.net`, `@lid`) passam: sao o que o
 * painel de fato usa.
 */
function enderecoIgnorado(jid: string | undefined | null): boolean {
    if (!jid) return false;
    const servidor = jid.includes('@') ? jid.slice(jid.indexOf('@') + 1) : '';
    return servidor === 'broadcast' || servidor === 'newsletter';
}

/**
 * Intervalo do ping que mantem o socket vivo.
 *
 * O padrao do Baileys e 30s. Aqui e mais curto porque uma VPS atras de NAT
 * perde conexao ociosa antes disso — e cada queda dessas custava uma
 * reconexao completa, com novo handshake e novas consultas de inicializacao.
 * Menos quedas e menos reconexao e exatamente o que reduz o volume de
 * requisicoes que a aplicacao faz ao WhatsApp.
 */
const KEEP_ALIVE_MS = 20_000;

/** Teto do handshake. Curto o bastante para nao ficar meia conexao pendurada. */
const CONNECT_TIMEOUT_MS = 30_000;

/** Teto de uma consulta (metadados de grupo, existencia de numero). */
const QUERY_TIMEOUT_MS = 45_000;

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

    /**
     * Abertura de socket em andamento.
     *
     * `openSocket()` tem tres pontos de espera antes de criar o socket (limpeza
     * da sessao invalidada, leitura das credenciais em disco e a consulta da
     * versao do WhatsApp Web, que sozinha pode levar 9s). Duas chamadas
     * concorrentes atravessavam essa janela juntas e criavam DOIS sockets para a
     * mesma conta — o servidor ve dois clientes web no mesmo aparelho, derruba
     * um com `connectionReplaced`, o que dispara outra reconexao, que abre outro
     * socket. E o loop que faz uma conta parecer estar sendo usada por varios
     * clientes ao mesmo tempo. Uma promessa unica fecha a porta.
     */
    private abrindo: Promise<void> | null = null;

    /**
     * Numero de serie do socket vivo.
     *
     * Cada listener guarda a geracao em que foi registrado e desiste quando ela
     * nao e mais a atual. Sem isso, um evento atrasado do socket anterior
     * (recibo, presenca, estado de conexao) era processado como se fosse do
     * novo: mensagem entrando duas vezes e estado de conexao voltando para tras
     * sozinho.
     */
    private geracao = 0;

    /** Como desassinar os listeners do socket atual. */
    private assinaturas: Array<() => void> = [];

    /**
     * Logger entregue ao Baileys, com o ruido de protocolo resumido.
     *
     * Separado do `this.logger` (que continua registrando tudo do adapter) para
     * o resumo valer so para o que a biblioteca emite. Um por adapter, e nao um
     * por socket: assim a explicacao sai uma vez por instancia, e nao uma vez
     * por reconexao.
     */
    private readonly loggerBaileys: Logger;

    private readonly messageCache = new MessageCache();

    /** Traducao LID -> telefone; ver LidDirectory para o porque. */
    private readonly lids = new LidDirectory();

    /**
     * Resolve quando o socket esta REALMENTE pronto para mandar stanza.
     *
     * `makeWASocket()` volta na hora, com o handshake ainda por acontecer:
     * quem chamar `requestPairingCode` logo depois de `connect()` cai no
     * `sendRawMessage` do Baileys com `ws.isOpen === false` e leva um
     * "Connection Closed" — que era exatamente o erro de quem tentava conectar
     * pelo numero de telefone.
     *
     * O sinal usado e o primeiro `qr` do `connection.update`: ele so e emitido
     * depois do handshake, e significa "o servidor aceitou a conexao e esta
     * esperando o pareamento". `connection: 'open'` tambem serve (sessao ja
     * pareada, sem QR nenhum).
     */
    private prontoParaParear: Promise<void> | null = null;
    private marcarPronto: (() => void) | null = null;

    /**
     * Pareamento por telefone em andamento: numero e codigo mostrados ao
     * operador.
     *
     * Guardado porque o codigo tem de SOBREVIVER A TROCA DE SOCKET. O Baileys
     * renova o QR a cada 20s e, quando as refs acabam (~2 min), derruba a
     * conexao com `QR refs attempts ended`; o adapter reconecta e o socket novo
     * nao tem registro de pareamento nenhum. Como abrir o WhatsApp, achar
     * "Aparelhos conectados", digitar o numero e digitar o codigo leva mais que
     * isso, o operador quase sempre digitava um codigo que ja estava morto — e
     * a tela nao tinha como saber.
     *
     * O Baileys aceita um codigo customizado de 8 caracteres em
     * `requestPairingCode(numero, codigo)`. E o que permite reapresentar o
     * MESMO codigo ao servidor a cada socket novo, mantendo valido o que o
     * operador esta digitando.
     */
    private pareamento: { numero: string; codigo: string } | null = null;

    /** Em qual socket o pareamento ja foi registrado (evita registrar 2x). */
    private pareamentoRegistradoEm: WASocket | null = null;

    constructor(
        private readonly options: AdapterOptions,
        private readonly logger: Logger,
        private readonly callbacks: BaileysAdapterCallbacks
    ) {
        this.loggerBaileys = loggerDoBaileys(options.instanceId);
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

    async connect(): Promise<void> {
        this.closedByUser = false;
        // Ja conectado ou conectando: NAO abre outro socket.
        //
        // `connect()` e chamado pelo boot do painel, pela tela de sessoes e
        // pelo watchdog. Antes, cada uma dessas chamadas abria mais um socket
        // por cima do que ja existia; a conta aparecia para o WhatsApp como
        // varios clientes web simultaneos, e a reconexao que o servidor
        // provoca ao derrubar o duplicado realimentava o ciclo.
        if (this.socket && (this.state === 'CONNECTED' || this.state === 'CONNECTING'
            || this.state === 'QR_PENDING' || this.state === 'PAIRING_CODE_PENDING')) {
            this.logger.debug({ state: this.state }, 'connect() ignorado: ja ha socket ativo');
            return;
        }
        await this.openSocket();
    }

    /**
     * Abre o socket, garantindo que exista no maximo um por instancia.
     *
     * A abertura e serializada por `this.abrindo`: enquanto uma esta em curso,
     * qualquer outra chamada espera a mesma promessa em vez de comecar a sua.
     */
    private async openSocket(): Promise<void> {
        if (this.abrindo) {
            return this.abrindo;
        }
        this.abrindo = this.abrirSocket().finally(() => {
            this.abrindo = null;
        });
        return this.abrindo;
    }

    private async abrirSocket(): Promise<void> {
        // Um temporizador de reconexao pendente abriria um segundo socket
        // alguns segundos depois deste.
        this.cancelarReconexao();
        // O socket anterior, se houver, sai de cena ANTES de o novo existir:
        // dois sockets vivos na mesma conta e o cenario que o WhatsApp trata
        // como uso anomalo.
        this.descartarSocket();

        const geracao = ++this.geracao;
        this.setState('CONNECTING', null, null);
        this.prontoParaParear = new Promise<void>((resolve) => {
            this.marcarPronto = resolve;
        });

        // A session invalidated by WhatsApp is deleted asynchronously; reading
        // the credentials before that finishes would load the very files that
        // are on their way out.
        if (this.sessionCleanup) {
            await this.sessionCleanup;
            this.sessionCleanup = null;
        }

        const { state, saveCreds } = await useMultiFileAuthState(this.options.sessionDirectory);

        // Perguntada ao WhatsApp, nao lida da constante do pacote: cliente
        // velho tem o pareamento recusado pelo servidor. Ver WaVersion.ts.
        // `null` = nao deu para descobrir; a chave e OMITIDA para o Baileys usar
        // a dele. Mandar `undefined` sobrescreveria o padrao e subiria o socket
        // sem versao nenhuma - sem QR e sem codigo de pareamento.
        const version = await resolverVersaoWhatsApp(this.logger);

        // Alguem desconectou/deslogou enquanto esperavamos o disco e a rede.
        if (this.geracao !== geracao) {
            this.logger.info('abertura abortada: outra operacao assumiu a instancia');
            return;
        }

        const socket = makeWASocket({
            auth: state,
            ...(version ? { version } : {}),
            logger: this.loggerBaileys as never,
            browser: [this.options.displayName, 'Chrome', '1.0.0'],
            markOnlineOnConnect: this.options.markOnlineOnConnect,
            keepAliveIntervalMs: KEEP_ALIVE_MS,
            connectTimeoutMs: CONNECT_TIMEOUT_MS,
            defaultQueryTimeoutMs: QUERY_TIMEOUT_MS,

            // ---- ACESSO MINIMO: o que este cliente NAO pede ao WhatsApp ----
            //
            // `syncFullHistory: false` sozinho ja desliga a sincronizacao de
            // historico nesta versao (o Baileys deriva `shouldSyncHistoryMessage`
            // dele quando a opcao nao e passada), mas essa derivacao e um
            // detalhe interno do pacote e uma atualizacao pode mudar o padrao
            // sem aviso. Declarar as duas deixa a intencao escrita no codigo: o
            // painel NAO importa conversa antiga do celular. O historico dele
            // comeca no momento em que a sessao conecta, e e so isso que ele
            // exibe.
            syncFullHistory: false,
            shouldSyncHistoryMessage: () => false,

            // Descarta antes de decifrar tudo que o painel nunca usa - em
            // especial o "Recado" de todos os contatos da agenda. Ver
            // enderecoIgnorado().
            shouldIgnoreJid: (jid) => enderecoIgnorado(jid),

            // O painel grava a mensagem que ele proprio envia no momento do
            // envio (WaChatStore.registrar, em WaEngineSdk). Com o eco ligado,
            // a mesma mensagem voltava pelo `messages.upsert` e era processada
            // de novo do zero - trabalho duplicado a cada envio de campanha.
            emitOwnEvents: false,

            // Previa de link exige baixar a pagina de destino. O painel nunca
            // mostrou previa nenhuma.
            generateHighQualityLinkPreview: false,

            // Quando o aparelho do destinatario nao consegue decifrar algo que
            // mandamos, ele pede o reenvio (retry receipt) e o Baileys chama
            // isto para recifrar. Sem a funcao, o pedido nunca e atendido: o
            // outro lado insiste, a sessao de criptografia com aquele contato
            // vai se degradando e o socket acaba caindo. O cache ja existia e
            // ja era alimentado em `messages.upsert` - so nao estava ligado.
            getMessage: async (key) => {
                const chatId = key.remoteJid;
                if (!chatId || !key.id) {
                    return undefined;
                }
                return this.messageCache.get(chatId, key.id)?.message ?? undefined;
            }
        });

        this.socket = socket;
        this.saveCreds = saveCreds;
        this.registrarListeners(socket, saveCreds, geracao);
    }

    /**
     * Liga os listeners de UM socket, presos a geracao dele.
     *
     * Cada assinatura vira uma funcao de cancelamento em `this.assinaturas`.
     * Antes elas eram registradas e nunca removidas: a cada reconexao o socket
     * novo trazia listeners novos e os do anterior continuavam vivos enquanto o
     * objeto existisse. O sintoma era mensagem processada duas, tres, N vezes -
     * uma por reconexao acumulada.
     */
    private registrarListeners(socket: WASocket, saveCreds: () => Promise<void>, geracao: number): void {
        const assinar = <T extends keyof BaileysEventMap>(
            evento: T,
            ouvinte: (arg: BaileysEventMap[T]) => void
        ): void => {
            const protegido = (arg: BaileysEventMap[T]): void => {
                // Evento atrasado de um socket que ja saiu de cena.
                if (this.geracao !== geracao) return;
                ouvinte(arg);
            };
            socket.ev.on(evento, protegido);
            this.assinaturas.push(() => socket.ev.off(evento, protegido));
        };

        assinar('creds.update', () => {
            // saveCreds do PROPRIO socket, e nao `this.saveCreds`: durante uma
            // troca de socket o campo ja aponta para o novo, e gravar as
            // credenciais de um pelo escritor do outro corrompe a sessao em
            // disco - que aparece depois como "pediu QR do nada".
            void saveCreds();
        });

        assinar('connection.update', (update) => this.handleConnectionUpdate(update));

        assinar('messages.upsert', ({ messages, type }) => {
            // `notify` = mensagem chegando agora.
            // `append` = mensagem que chegou enquanto estavamos offline (o
            //            Baileys marca o stanza com `offline`). NAO e
            //            sincronizacao de historico - essa vem por
            //            `messaging-history.set`, que este adapter nunca
            //            assina. Descartar `append` perderia respostas reais
            //            recebidas durante uma queda de conexao.
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

        // NAO ha listener de `contacts.upsert`/`contacts.update` aqui, e a
        // ausencia e deliberada. `contacts.upsert` entrega a AGENDA INTEIRA da
        // conta de uma vez, e o adapter guardava o par LID/telefone de cada
        // contato - inclusive de gente com quem o painel nunca trocou uma
        // mensagem. Era coleta de dado de terceiro sem nenhum uso: o unico
        // motivo de existir do LidDirectory e nao separar em duas a conversa de
        // quem escreveu para nos, e para isso learnLids(msg) - que le o par no
        // proprio stanza daquela conversa - resolve por completo.

        assinar('messages.update', (updates) => {
            for (const update of updates) {
                const chatId = update.key.remoteJid;
                const messageId = update.key.id;
                if (!chatId || !messageId) continue;
                this.callbacks.onMessageUpdated(chatId, messageId, update.update);
            }
        });

        assinar('messages.delete', (payload) => {
            if ('keys' in payload) {
                for (const key of payload.keys) {
                    if (key.remoteJid && key.id) {
                        this.callbacks.onMessageDeleted(key.remoteJid, key.id);
                    }
                }
            }
        });

        assinar('messages.reaction', (reactions) => {
            for (const r of reactions) {
                const chatId = r.key.remoteJid;
                const messageId = r.key.id;
                if (!chatId || !messageId) continue;
                this.callbacks.onReaction(chatId, messageId, r.reaction.key?.participant ?? chatId, r.reaction.text ?? null);
            }
        });

        assinar('groups.update', (updates) => {
            for (const update of updates) {
                if (update.id) {
                    this.callbacks.onGroupUpdated(update.id, update);
                }
            }
        });

        assinar('group-participants.update', (event) => {
            this.callbacks.onGroupUpdated(event.id, event);
        });

        assinar('presence.update', (event) => {
            this.callbacks.onPresenceUpdated(event.id, event.presences);
        });
    }

    /**
     * Tira o socket atual de cena: desassina, fecha e esquece.
     *
     * Chamado antes de abrir outro e nas saidas deliberadas. Fechar sem
     * desassinar deixaria os callbacks do socket morto disparando; desassinar
     * sem fechar deixaria a conexao viva contando como mais um cliente web na
     * conta.
     */
    private descartarSocket(): void {
        for (const cancelar of this.assinaturas) {
            try {
                cancelar();
            } catch {
                // Emissor ja descartado: nada a fazer.
            }
        }
        this.assinaturas = [];

        const socket = this.socket;
        this.socket = null;
        this.saveCreds = null;
        if (!socket) return;
        try {
            socket.end(undefined);
        } catch {
            // Ja fechado.
        }
    }

    /** Cancela uma reconexao agendada, se houver. */
    private cancelarReconexao(): void {
        if (this.reconnectTimer) {
            clearTimeout(this.reconnectTimer);
            this.reconnectTimer = null;
        }
    }

    private handleConnectionUpdate(update: {
        connection?: string;
        qr?: string;
        lastDisconnect?: { error?: Error };
    }): void {
        if (update.qr) {
            // Handshake concluido: o socket ja aceita stanza. E o que
            // requestPairingCode espera.
            this.marcarPronto?.();

            // Com pareamento por telefone em curso o QR nao interessa: o
            // operador esta digitando um codigo. Emiti-lo trocaria o estado
            // para QR_PENDING no meio do processo e, pior, deixaria o socket
            // seguir para o fim das refs e morrer levando o codigo junto.
            if (this.pareamento) {
                this.reregistrarPareamento();
                return;
            }

            this.currentQr = update.qr;
            this.setState('QR_PENDING', null, null);
            this.callbacks.onQr(update.qr);
            return;
        }

        if (update.connection === 'open') {
            this.marcarPronto?.();
            this.currentQr = null;
            this.reconnectAttempts = 0;
            this.pareamento = null;          // pareou: o codigo cumpriu o papel
            this.pareamentoRegistradoEm = null;
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

        // Desassina e fecha o socket morto. Antes so o campo era zerado: o
        // objeto continuava com todos os listeners ligados e, se o WebSocket
        // ainda estivesse meio vivo, seus eventos seguiam sendo processados
        // junto com os do socket seguinte.
        this.descartarSocket();

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

        // Com pareamento em curso, a queda e ESPERADA: o Baileys derruba o
        // socket quando as refs de QR acabam (~2 min). Nao e falha, entao nao
        // pode alimentar o backoff exponencial — cada segundo aqui e um
        // segundo em que o codigo que o operador esta digitando nao existe do
        // lado do WhatsApp.
        if (this.pareamento) {
            this.reconnectAttempts = 0;
            this.pareamentoRegistradoEm = null;
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
        this.cancelarReconexao();

        // Saida deliberada (disconnect/logout) nao reconecta. Sem esta
        // conferencia, um close que chegasse depois do desligamento explicito
        // religava a conta sozinha.
        if (this.closedByUser) {
            return;
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
            this.reconnectTimer = null;
            if (this.closedByUser) {
                return;
            }
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

    /**
     * Codigo de pareamento por numero de telefone (a alternativa ao QR).
     *
     * Duas coisas tem de ser verdade antes de pedir o codigo, e nenhuma delas
     * era garantida:
     *
     * 1. **O socket precisa estar pronto para mandar stanza.**
     *    `makeWASocket()` volta antes do handshake terminar, e o Baileys
     *    responde `Connection Closed` a qualquer envio nesse intervalo. Por
     *    isso esperamos `prontoParaParear` — ver o campo para o sinal usado.
     * 2. **A sessao nao pode ja estar pareada.** Com `creds.registered`, o
     *    WhatsApp nao emite QR nem aceita pareamento, e a espera acima
     *    estouraria no timeout sem explicar nada.
     */
    async requestPairingCode(phoneNumber: string): Promise<string> {
        const numero = phoneNumber.replace(/\D/g, '');
        if (numero.length < 10 || numero.length > 15) {
            throw new BridgeCommandError(
                ErrorCode.INVALID_PAYLOAD,
                `numero invalido: "${phoneNumber}". Use so digitos, com DDI (ex.: 5562999998888).`
            );
        }
        if (!this.socket) {
            throw new BridgeCommandError(ErrorCode.NOT_CONNECTED, 'Instancia nao esta conectando; chame connect antes');
        }
        if (this.socket.authState.creds.registered) {
            throw new BridgeCommandError(
                ErrorCode.INVALID_PAYLOAD,
                'esta sessao ja esta pareada; desconecte-a antes de parear outro numero'
            );
        }

        this.setState('PAIRING_CODE_PENDING', null, null);
        await this.esperarSocketPronto();

        const code = await this.socket.requestPairingCode(numero);
        this.pareamento = { numero, codigo: code };
        this.pareamentoRegistradoEm = this.socket;
        this.logger.info({ numero }, 'codigo de pareamento gerado');
        this.callbacks.onPairingCode(code);
        return code;
    }

    /**
     * Reapresenta ao servidor o MESMO codigo, no socket atual.
     *
     * Chamado quando o socket novo fica pronto com um pareamento ainda em
     * curso. Sem isto, o codigo na tela do painel deixa de existir do lado do
     * WhatsApp assim que a conexao anterior cai — e digitar no celular so
     * responde "codigo invalido".
     *
     * Falha aqui e logada, nao propagada: nao ha ninguem esperando por esta
     * chamada (ela nasce de um evento), e a proxima reconexao tenta de novo.
     */
    private reregistrarPareamento(): void {
        const p = this.pareamento;
        const socket = this.socket;
        if (!p || !socket || this.pareamentoRegistradoEm === socket) {
            return;
        }
        this.pareamentoRegistradoEm = socket;
        this.setState('PAIRING_CODE_PENDING', null, null);
        socket
            .requestPairingCode(p.numero, p.codigo)
            .then(() => this.logger.info({ numero: p.numero }, 'codigo de pareamento revalidado apos reconexao'))
            .catch((err) => {
                this.pareamentoRegistradoEm = null;
                this.logger.warn({ err }, 'nao consegui revalidar o codigo de pareamento; tentando na proxima reconexao');
            });
    }

    /** Espera o handshake terminar, com teto para nao travar a rota do painel. */
    private async esperarSocketPronto(): Promise<void> {
        if (!this.prontoParaParear) {
            return;
        }
        let temporizador: NodeJS.Timeout | undefined;
        const estouro = new Promise<never>((_, reject) => {
            temporizador = setTimeout(
                () => reject(new BridgeCommandError(
                    ErrorCode.NOT_CONNECTED,
                    'o WhatsApp nao respondeu a tempo; verifique a internet e tente de novo'
                )),
                PAIRING_READY_TIMEOUT_MS
            );
        });
        try {
            await Promise.race([this.prontoParaParear, estouro]);
        } finally {
            if (temporizador) clearTimeout(temporizador);
        }
    }

    async disconnect(): Promise<void> {
        this.closedByUser = 'disconnect';
        this.pareamento = null;
        this.pareamentoRegistradoEm = null;
        this.cancelarReconexao();
        // Invalida a geracao: uma abertura que esteja esperando disco ou rede
        // neste instante descobre, ao voltar, que ja nao e ela quem manda e
        // desiste em vez de subir um socket para uma instancia desligada.
        this.geracao++;
        this.descartarSocket();
        // Also set explicitly (not just left to handleClose()'s reaction to Baileys' own
        // 'connection.update' close event): if there was no active socket, that event never fires.
        this.setState('DISCONNECTED', 'USER_LOGOUT', 'disconnect() chamado explicitamente');
    }

    async logout(): Promise<void> {
        this.closedByUser = 'logout';
        this.pareamento = null;
        this.pareamentoRegistradoEm = null;
        this.cancelarReconexao();
        try {
            await this.socket?.logout();
        } catch (err) {
            this.logger.warn({ err }, 'logout() no baileys falhou, prosseguindo com limpeza local');
        }
        // Mesma razao do disconnect(): nenhuma abertura em voo pode ressuscitar
        // uma sessao cujas credenciais estao sendo apagadas logo abaixo.
        this.geracao++;
        this.descartarSocket();
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
        let timer: NodeJS.Timeout | undefined;
        try {
            const timeoutPromise = new Promise<never>((_, reject) => {
                timer = setTimeout(() => {
                    reject(new BridgeCommandError(ErrorCode.TIMEOUT, 'Tempo limite esgotado ao enviar mensagem pelo Baileys (100s)'));
                }, 100_000);
                timer.unref?.();
            });
            const result = await Promise.race([
                socket.sendMessage(to, content, { quoted }),
                timeoutPromise
            ]);
            if (!result?.key.id) {
                throw new BridgeCommandError(ErrorCode.SEND_FAILED, 'Baileys nao retornou um id de mensagem');
            }
            // O reenvio (retry receipt) e pedido para o que NOS mandamos, entao
            // e esta copia que o `getMessage` vai procurar. Sem guardar aqui, o
            // cache so teria mensagem recebida e o pedido de reenvio cairia no
            // vazio — que e o caso que a funcao existe para atender.
            this.messageCache.put(result);
            return result.key.id;
        } catch (err) {
            if (err instanceof BridgeCommandError) throw err;
            throw new BridgeCommandError(ErrorCode.SEND_FAILED, (err as Error).message);
        } finally {
            if (timer) clearTimeout(timer);
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
    /**
     * Publishes our own presence ("typing...", "online", "offline").
     *
     * Three things have to line up or the indicator silently never shows, with
     * no error anywhere:
     *
     * 1. **The socket has to be online first.** With `markOnlineOnConnect:
     *    false` — which is what we want, so the number is not online around
     *    the clock — WhatsApp drops `composing` from an offline socket.
     *
     * 2. **The address has to match how the chat is identified.** A regular
     *    message is resolved server-side whichever address you use, but a
     *    `chatstate` is delivered verbatim: once a conversation has migrated
     *    to LID, `composing` sent to `<phone>@s.whatsapp.net` never reaches
     *    the open chat. So we address the LID whenever we know it.
     *
     * 3. **The account needs a profile name.** Baileys refuses to send
     *    `available`/`unavailable` when `creds.me.name` is empty (it logs
     *    "no name present, ignoring presence update request" and returns), and
     *    without `available` the socket stays offline and item 1 bites. There
     *    is nothing we can do about it from here except say so clearly.
     */
    async sendPresence(chatId: string, presence: WAPresence): Promise<void> {
        const socket = this.requireSocket();
        const alvo = presence === 'available' || presence === 'unavailable'
            ? chatId
            : this.lids.toLid(chatId);
        try {
            if (presence === 'composing' || presence === 'recording') {
                if (!socket.user?.name) {
                    this.logger.warn(
                        { chatId },
                        'a conta conectada nao tem nome de perfil: o Baileys ignora "available" e o '
                        + '"digitando..." nao aparece. Defina um nome no perfil do WhatsApp.'
                    );
                }
                await socket.sendPresenceUpdate('available');
                await socket.presenceSubscribe(alvo);
            }
            await socket.sendPresenceUpdate(presence, alvo);
            this.logger.debug(
                { presence, alvo, traduzido: alvo !== chatId },
                'presenca publicada'
            );
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

    /**
     * Replaces the text of a message we already sent.
     *
     * The edit travels as a normal message carrying the original key, so the
     * result has its own id - which we cache like any other outbound message,
     * otherwise a retry receipt for it would find nothing.
     *
     * WhatsApp closes the edit window a few minutes after delivery and does
     * not publish the exact limit. Past it the server simply refuses, and that
     * refusal is what the caller sees: there is no way to ask in advance
     * whether a given message is still editable.
     */
    async editMessage(payload: Content.EditMessagePayload): Promise<string> {
        const socket = this.requireSocket();
        try {
            const result = await socket.sendMessage(payload.chatId, Content.buildEditContent(payload));
            if (result) {
                this.messageCache.put(result);
            }
            return result?.key.id ?? payload.messageId;
        } catch (err) {
            throw new BridgeCommandError(ErrorCode.SEND_FAILED, (err as Error).message);
        }
    }

    /**
     * Revokes a message for everyone ("apagar para todos").
     *
     * Deleting only on our side is deliberately not offered: the panel keeps
     * its own history, so hiding a message locally is a job for the panel, not
     * for WhatsApp.
     */
    async deleteMessage(payload: Content.MessageTargetPayload): Promise<void> {
        const socket = this.requireSocket();
        try {
            await socket.sendMessage(payload.chatId, Content.buildDeleteContent(payload));
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

    /**
     * Apenas a URL da foto de perfil - uma consulta, e so o dado que a tela usa.
     *
     * Existe para o painel parar de chamar `getContact()` quando tudo o que ele
     * quer e o avatar. Aquele metodo dispara TRES consultas por contato:
     * `onWhatsApp` (o numero existe?), `profilePictureUrl` (a foto) e
     * `fetchStatus` (o "recado" pessoal da pessoa). A tela de conversas pede o
     * avatar de cada contato da barra lateral, entao abrir o painel virava uma
     * varredura da agenda inteira - sondagem de existencia de numero e leitura
     * de recado pessoal de gente com quem o operador nem estava falando, dois
     * dados que o painel nunca exibiu. E leitura em massa de informacao de
     * terceiro, e e o comportamento que faz uma conta ser suspensa.
     *
     * `null` quando o contato nao tem foto ou nao a mostra para nos - o caso
     * comum, e por isso nao e erro.
     */
    async getProfilePicture(jid: string): Promise<{ jid: string; profilePictureUrl: string | null }> {
        const socket = this.requireSocket();
        try {
            const url = (await socket.profilePictureUrl(jid, 'image')) ?? null;
            return { jid, profilePictureUrl: url };
        } catch {
            return { jid, profilePictureUrl: null };
        }
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
