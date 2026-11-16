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
    alwaysOnline?: boolean;
    includeRawData?: boolean;
    acceptSince?: number;
    readsPerMinute?: number;
    minReconnectDelayMs?: number;
    maxReconnectDelayMs?: number;
    maxLoginCycles?: number;
}

/** OS version Chrome reports on Windows 11; the tuple is [os, browser, os version]. */
const DEFAULT_OS_VERSION = '10.0.22631';

/**
 * Every account hosted by this bridge process. Enforces that an instance id is
 * unique and that no two accounts share a session directory; the on-disk
 * SessionLock extends the second guarantee to other processes, because two
 * sockets on one account make WhatsApp answer connectionReplaced in a loop.
 */
export class InstanceManager {
    private readonly instances = new Map<string, WhatsAppInstance>();
    private readonly directories = new Map<string, string>();
    private readonly locks = new Map<string, SessionLock>();

    constructor(
        private readonly logger: Logger,
        private readonly sink: EventSink
    ) {}

    create(options: CreateInstanceOptions): WhatsAppInstance {
        const existing = this.instances.get(options.instanceId);
        const directory = resolve(options.sessionDirectory);
        if (existing) {
            // A Java client reattaching after a lost WebSocket: same account, same directory, nothing to redo.
            if (existing.sessionDirectory === directory) {
                return existing;
            }
            throw new BridgeCommandError(ErrorCode.INSTANCE_ALREADY_EXISTS, `instance '${options.instanceId}' already exists`);
        }
        const owner = this.directories.get(directory);
        if (owner) {
            throw new BridgeCommandError(ErrorCode.INSTANCE_ALREADY_EXISTS, `session directory '${directory}' is used by '${owner}'`);
        }

        const lock = SessionLock.acquire(directory, options.instanceId, this.logger);
        let instance: WhatsAppInstance;
        try {
            instance = new WhatsAppInstance(
                {
                    instanceId: options.instanceId,
                    sessionDirectory: directory,
                    browser: [options.displayName ?? 'Windows', 'Chrome', DEFAULT_OS_VERSION],
                    alwaysOnline: options.alwaysOnline ?? true,
                    includeRawData: options.includeRawData ?? false,
                    acceptSince: options.acceptSince ?? Math.floor(Date.now() / 1000),
                    readsPerMinute: options.readsPerMinute ?? 30,
                    minReconnectDelayMs: options.minReconnectDelayMs ?? 1_000,
                    maxReconnectDelayMs: options.maxReconnectDelayMs ?? 30_000,
                    maxLoginCycles: options.maxLoginCycles ?? 5
                },
                this.logger.child({ instanceId: options.instanceId }),
                this.sink
            );
        } catch (err) {
            lock.release();
            throw err;
        }
        this.instances.set(options.instanceId, instance);
        this.directories.set(directory, options.instanceId);
        this.locks.set(options.instanceId, lock);
        this.logger.info({ instanceId: options.instanceId }, 'instance created');
        return instance;
    }

    get(instanceId: string): WhatsAppInstance {
        const instance = this.instances.get(instanceId);
        if (!instance) {
            throw new BridgeCommandError(ErrorCode.INSTANCE_NOT_FOUND, `instance '${instanceId}' not found`);
        }
        return instance;
    }

    list(): WhatsAppInstance[] {
        return [...this.instances.values()];
    }

    async destroy(instanceId: string): Promise<void> {
        const instance = this.instances.get(instanceId);
        if (!instance) return;
        await instance.adapter.disconnect();
        instance.adapter.destroy();
        this.instances.delete(instanceId);
        this.directories.delete(instance.sessionDirectory);
        this.releaseLock(instanceId);
        this.logger.info({ instanceId }, 'instance destroyed');
    }

    async shutdownAll(): Promise<void> {
        await Promise.all([...this.instances.keys()].map((id) => this.destroy(id)));
    }

    /** For the synchronous exit paths, where there is no time to await a disconnect. */
    releaseAllLocks(): void {
        for (const id of [...this.locks.keys()]) {
            this.releaseLock(id);
        }
    }

    private releaseLock(instanceId: string): void {
        this.locks.get(instanceId)?.release();
        this.locks.delete(instanceId);
    }
}
