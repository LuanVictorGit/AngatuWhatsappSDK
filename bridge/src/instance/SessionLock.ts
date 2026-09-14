import { randomBytes } from 'node:crypto';
import { mkdirSync, openSync, writeSync, fsyncSync, closeSync, readFileSync, unlinkSync } from 'node:fs';
import { join, resolve } from 'node:path';
import type { Logger } from '../util/logger.js';
import { BridgeCommandError, ErrorCode } from '../protocol/errors.js';

/** Nome do arquivo de trava dentro do diretorio de sessao. */
const LOCK_FILE = '.lock';

/**
 * Conteudo gravado na trava. O `boot` existe por causa de reuso de PID: um
 * sistema que ficou meses no ar pode reciclar o numero de um processo morto, e
 * sem um valor aleatorio a trava orfa passaria por viva para sempre.
 */
interface LockContent {
    pid: number;
    boot: string;
    startedAt: number;
    instanceId: string;
}

/** Identificador aleatorio deste processo, gerado uma vez. */
const BOOT_ID = randomBytes(8).toString('hex');

/**
 * Trava de diretorio de sessao entre processos.
 *
 * <b>Por que isto existe.</b> O {@link InstanceManager} ja impede duas
 * instancias no mesmo diretorio, e o `InstanceRegistry` do lado Java faz o
 * mesmo dentro da JVM — mas os dois sao mapas em memoria. Cada
 * `WhatsAppClient` sobe seu PROPRIO processo bridge, e um `kill -9` na JVM (ou
 * um `docker stop`, ou um OOM) nao mata o processo filho: sobra um bridge orfao
 * com socket vivo no WhatsApp. Quando o app volta, os mapas estao vazios, um
 * segundo bridge abre outro socket na MESMA conta, e o servidor derruba um com
 * `connectionReplaced` — que dispara reconexao, que abre outro socket. Do ponto
 * de vista do WhatsApp e uma conta com varios clientes web aparecendo e
 * sumindo: a assinatura de automacao que leva a suspensao.
 *
 * O arquivo em disco e a unica coisa que sobrevive aos dois processos, entao e
 * nele que a garantia "uma conta, um socket" passa a morar de verdade.
 *
 * A criacao usa `flag: 'wx'` (O_EXCL): o proprio sistema de arquivos garante
 * que so um processo cria o arquivo, sem janela entre "conferir" e "criar".
 */
export class SessionLock {
    private liberada = false;

    private constructor(
        private readonly caminho: string,
        private readonly conteudo: LockContent,
        private readonly logger: Logger
    ) {}

    /**
     * Toma a trava do diretorio, criando o diretorio se preciso.
     *
     * @throws BridgeCommandError se outro processo vivo ja a detem
     */
    static acquire(sessionDirectory: string, instanceId: string, logger: Logger): SessionLock {
        const dir = resolve(sessionDirectory);
        mkdirSync(dir, { recursive: true });
        const caminho = join(dir, LOCK_FILE);
        const conteudo: LockContent = {
            pid: process.pid,
            boot: BOOT_ID,
            startedAt: Date.now(),
            instanceId
        };

        try {
            SessionLock.escrever(caminho, conteudo);
        } catch (err) {
            if ((err as NodeJS.ErrnoException).code !== 'EEXIST') {
                throw err;
            }
            const dono = SessionLock.lerDono(caminho, logger);
            if (dono && SessionLock.processoVivo(dono)) {
                throw new BridgeCommandError(
                    ErrorCode.INSTANCE_ALREADY_EXISTS,
                    `O diretorio de sessao '${dir}' ja esta em uso pelo processo ${dono.pid}` +
                        ` (instancia '${dono.instanceId}'). Duas conexoes na mesma conta fazem o WhatsApp` +
                        ` derrubar uma com connectionReplaced. Encerre o outro processo; se ele ja morreu,` +
                        ` apague o arquivo '${caminho}'.`,
                    { sessionDirectory: dir, ownerPid: dono.pid, ownerInstanceId: dono.instanceId }
                );
            }
            // Trava orfa: o dono morreu sem liberar. Tomar e o caminho certo —
            // recusar aqui deixaria a conta inacessivel ate alguem apagar o
            // arquivo a mao, que e exatamente o tipo de manutencao manual que
            // esta trava existe para evitar.
            logger.warn(
                { caminho, donoAnterior: dono?.pid ?? null },
                'trava de sessao orfa encontrada (processo dono nao existe mais) - assumindo o diretorio'
            );
            try {
                unlinkSync(caminho);
            } catch {
                // corrida com outro processo fazendo a mesma limpeza: o write abaixo decide
            }
            SessionLock.escrever(caminho, conteudo);
        }

        logger.debug({ caminho }, 'trava de sessao adquirida');
        return new SessionLock(caminho, conteudo, logger);
    }

    /**
     * Devolve a trava. Idempotente, e nunca lanca: e chamada em caminho de
     * encerramento, onde falhar seria pior que o defeito que a trava cobre.
     */
    release(): void {
        if (this.liberada) {
            return;
        }
        this.liberada = true;
        try {
            // So apaga se ainda for nossa: um bridge que perdeu a trava para
            // outro (por ter sido considerado orfao) nao pode apagar a trava
            // legitima de quem assumiu.
            const dono = SessionLock.lerDono(this.caminho, this.logger);
            if (dono && (dono.pid !== this.conteudo.pid || dono.boot !== this.conteudo.boot)) {
                this.logger.warn({ caminho: this.caminho }, 'trava de sessao pertence a outro processo - nao removida');
                return;
            }
            unlinkSync(this.caminho);
            this.logger.debug({ caminho: this.caminho }, 'trava de sessao liberada');
        } catch (err) {
            if ((err as NodeJS.ErrnoException).code !== 'ENOENT') {
                this.logger.warn({ err, caminho: this.caminho }, 'falha ao liberar a trava de sessao');
            }
        }
    }

    /** Grava a trava com O_EXCL e fsync — a durabilidade importa num crash. */
    private static escrever(caminho: string, conteudo: LockContent): void {
        const fd = openSync(caminho, 'wx');
        try {
            writeSync(fd, JSON.stringify(conteudo));
            fsyncSync(fd);
        } finally {
            closeSync(fd);
        }
    }

    private static lerDono(caminho: string, logger: Logger): LockContent | null {
        try {
            const bruto = readFileSync(caminho, 'utf8');
            const dados = JSON.parse(bruto) as Partial<LockContent>;
            if (typeof dados.pid !== 'number') {
                return null;
            }
            return {
                pid: dados.pid,
                boot: typeof dados.boot === 'string' ? dados.boot : '',
                startedAt: typeof dados.startedAt === 'number' ? dados.startedAt : 0,
                instanceId: typeof dados.instanceId === 'string' ? dados.instanceId : '?'
            };
        } catch (err) {
            // Trava ilegivel (truncada por queda no meio da escrita) conta como
            // orfa: nao ha dono a respeitar num arquivo que nao diz quem e.
            logger.warn({ err, caminho }, 'trava de sessao ilegivel - tratada como orfa');
            return null;
        }
    }

    /**
     * Se o processo dono ainda existe.
     *
     * O sinal 0 nao entrega nada: so consulta. `ESRCH` e a unica resposta que
     * significa "nao existe" — `EPERM` quer dizer que existe e pertence a outro
     * usuario, e nesse caso respeitar a trava e o certo.
     */
    private static processoVivo(dono: LockContent): boolean {
        if (dono.pid === process.pid && dono.boot === BOOT_ID) {
            return true; // somos nos mesmos
        }
        try {
            process.kill(dono.pid, 0);
            return true;
        } catch (err) {
            return (err as NodeJS.ErrnoException).code !== 'ESRCH';
        }
    }
}
