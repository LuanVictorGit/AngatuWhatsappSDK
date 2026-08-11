import { resolve } from 'node:path';
import { WhatsAppInstance } from './WhatsAppInstance.js';
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
 */
export class InstanceManager {
    private readonly instances = new Map<string, WhatsAppInstance>();
    private readonly sessionDirsInUse = new Map<string, string>();

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

        const instance = new WhatsAppInstance(
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

        this.instances.set(options.instanceId, instance);
        this.sessionDirsInUse.set(resolvedDir, options.instanceId);
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
        this.instances.delete(instanceId);
        this.sessionDirsInUse.delete(resolve(instance.sessionDirectory));
        this.logger.info({ instanceId }, 'instance destroyed');
    }

    async shutdownAll(): Promise<void> {
        await Promise.all([...this.instances.values()].map((instance) => instance.adapter.disconnect()));
        this.instances.clear();
        this.sessionDirsInUse.clear();
    }
}
