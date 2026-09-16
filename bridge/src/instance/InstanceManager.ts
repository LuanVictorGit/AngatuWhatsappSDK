import { resolve } from 'node:path';
import { WhatsAppInstance } from './WhatsAppInstance.js';
import { SessionLock } from './SessionLock.js';
import type { EventSink } from './EventSink.js';
import type { Logger } from '../util/logger.js';
import { BridgeCommandError, ErrorCode } from '../protocol/errors.js';

export interface CreateInstanceOptions {
    instanceId: string;
    sessionDirectory: string;
    displayName?: string;
    markOnlineOnConnect?: boolean;
    minReconnectDelayMs?: number;
    maxReconnectDelayMs?: number;
}

/**
 * Owns every WhatsAppInstance running in this bridge process and enforces
 * the invariants the SDK spec requires: an instanceId is unique, and two
 * instances can never share a session directory (that would corrupt both
 * Baileys auth states, since useMultiFileAuthState writes freely to it).
 *
 * O mapa {@link sessionDirsInUse} cobre so este processo. A garantia ENTRE
 * processos — um bridge orfao de um `kill -9` anterior, ou uma segunda JVM —
 * vem da {@link SessionLock}, um arquivo no proprio diretorio de sessao. Ver o
 * Javadoc dela para o porque: dois sockets na mesma conta e o que faz o
 * WhatsApp responder `connectionReplaced` em looping.
 */
export class InstanceManager {
    private readonly instances = new Map<string, WhatsAppInstance>();
    private readonly sessionDirsInUse = new Map<string, string>();
    private readonly locks = new Map<string, SessionLock>();

    constructor(
        private readonly logger: Logger,
        private readonly eventSink: EventSink
    ) {}

    create(options: CreateInstanceOptions): WhatsAppInstance {
        if (this.instances.has(options.instanceId)) {
            throw new BridgeCommandError(
                ErrorCode.INSTANCE_ALREADY_EXISTS,
                `Instancia '${options.instanceId}' ja existe neste processo bridge`
            );
        }

        const resolvedDir = resolve(options.sessionDirectory);
        const owner = this.sessionDirsInUse.get(resolvedDir);
        if (owner) {
            throw new BridgeCommandError(
                ErrorCode.INSTANCE_ALREADY_EXISTS,
                `sessionDirectory '${resolvedDir}' ja esta em uso pela instancia '${owner}'`
            );
        }

        // Antes de qualquer coisa: a trava em disco. Se outro processo detem a
        // conta, e melhor falhar aqui do que subir um socket que vai brigar com
        // o dele.
        const lock = SessionLock.acquire(resolvedDir, options.instanceId, this.logger);

        let instance: WhatsAppInstance;
        try {
            instance = new WhatsAppInstance(
                {
                    instanceId: options.instanceId,
                    sessionDirectory: resolvedDir,
                    displayName: options.displayName ?? 'Angatu WhatsApp SDK',
                    markOnlineOnConnect: options.markOnlineOnConnect ?? false,
                    minReconnectDelayMs: options.minReconnectDelayMs ?? 1_000,
                    maxReconnectDelayMs: options.maxReconnectDelayMs ?? 30_000
                },
                this.logger.child({ instanceId: options.instanceId }),
                this.eventSink
            );
        } catch (err) {
            // Sem isto, um erro de construcao deixaria a trava no disco sem
            // dono vivo para libera-la — e a conta ficaria inacessivel ate o
            // proximo processo considerar a trava orfa.
            lock.release();
            throw err;
        }

        this.instances.set(options.instanceId, instance);
        this.sessionDirsInUse.set(resolvedDir, options.instanceId);
        this.locks.set(options.instanceId, lock);
        this.logger.info({ instanceId: options.instanceId }, 'instance created');
        return instance;
    }

    get(instanceId: string): WhatsAppInstance {
        const instance = this.instances.get(instanceId);
        if (!instance) {
            throw new BridgeCommandError(ErrorCode.INSTANCE_NOT_FOUND, `Instancia '${instanceId}' nao encontrada`);
        }
        return instance;
    }

    has(instanceId: string): boolean {
        return this.instances.has(instanceId);
    }

    list(): string[] {
        return [...this.instances.keys()];
    }

    async destroy(instanceId: string): Promise<void> {
        const instance = this.get(instanceId);
        await instance.adapter.disconnect();
        // Depois do disconnect: a instancia nao volta mais, entao o
        // temporizador de manutencao e os contadores dela vao junto.
        instance.adapter.destroy();
        this.instances.delete(instanceId);
        this.sessionDirsInUse.delete(resolve(instance.sessionDirectory));
        this.liberarTrava(instanceId);
        this.logger.info({ instanceId }, 'instance destroyed');
    }

    async shutdownAll(): Promise<void> {
        await Promise.all([...this.instances.values()].map(async (instance) => {
            await instance.adapter.disconnect();
            instance.adapter.destroy();
        }));
        this.instances.clear();
        this.sessionDirsInUse.clear();
        for (const instanceId of [...this.locks.keys()]) {
            this.liberarTrava(instanceId);
        }
    }

    /**
     * Libera as travas sem esperar desconexao nenhuma.
     *
     * Existe para os caminhos sincronos de encerramento (`exit`, sinal fatal),
     * onde nao ha tempo para um `await`: perder a trava no disco custaria ao
     * proximo processo esperar ela vencer como orfa.
     */
    releaseAllLocks(): void {
        for (const instanceId of [...this.locks.keys()]) {
            this.liberarTrava(instanceId);
        }
    }

    private liberarTrava(instanceId: string): void {
        const lock = this.locks.get(instanceId);
        if (lock) {
            lock.release();
            this.locks.delete(instanceId);
        }
    }
}
