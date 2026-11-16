import { randomBytes } from 'node:crypto';
import { mkdirSync, openSync, writeSync, fsyncSync, closeSync, readFileSync, unlinkSync } from 'node:fs';
import { join, resolve } from 'node:path';
import type { Logger } from '../util/logger.js';
import { BridgeCommandError, ErrorCode } from '../protocol/errors.js';

const LOCK_FILE = '.lock';

interface LockContent {
    pid: number;
    boot: string;
    startedAt: number;
    instanceId: string;
}

/** Tells this process apart from an earlier one that reused the same pid. */
const BOOT_ID = randomBytes(8).toString('hex');

/**
 * One process per session directory, across processes. An orphaned bridge
 * (its JVM killed with -9) or a second JVM would otherwise open a second socket
 * on the account, and WhatsApp drops one of them with connectionReplaced in a
 * loop. A lock whose owner is no longer alive is taken over. Not valid over
 * NFS/SMB, where `wx` is not atomic.
 */
export class SessionLock {
    private released = false;

    private constructor(
        private readonly path: string,
        private readonly content: LockContent,
        private readonly logger: Logger
    ) {}

    static acquire(sessionDirectory: string, instanceId: string, logger: Logger): SessionLock {
        const directory = resolve(sessionDirectory);
        mkdirSync(directory, { recursive: true });
        const path = join(directory, LOCK_FILE);
        const content: LockContent = { pid: process.pid, boot: BOOT_ID, startedAt: Date.now(), instanceId };
        try {
            SessionLock.write(path, content);
        } catch (err) {
            if ((err as NodeJS.ErrnoException).code !== 'EEXIST') {
                throw err;
            }
            const owner = SessionLock.readOwner(path, logger);
            if (owner && SessionLock.isAlive(owner)) {
                throw new BridgeCommandError(
                    ErrorCode.INSTANCE_ALREADY_EXISTS,
                    `session directory '${directory}' is in use by process ${owner.pid} (instance '${owner.instanceId}'); ` +
                        `stop that process, or delete '${path}' if it is gone`,
                    { sessionDirectory: directory, ownerPid: owner.pid, ownerInstanceId: owner.instanceId }
                );
            }
            logger.warn({ path, previousOwner: owner?.pid ?? null }, 'orphaned session lock found - taking over the directory');
            try {
                unlinkSync(path);
            } catch {
                // removed concurrently
            }
            SessionLock.write(path, content);
        }
        return new SessionLock(path, content, logger);
    }

    release(): void {
        if (this.released) return;
        this.released = true;
        try {
            const owner = SessionLock.readOwner(this.path, this.logger);
            if (owner && (owner.pid !== this.content.pid || owner.boot !== this.content.boot)) {
                this.logger.warn({ path: this.path }, 'session lock belongs to another process - left in place');
                return;
            }
            unlinkSync(this.path);
        } catch (err) {
            if ((err as NodeJS.ErrnoException).code !== 'ENOENT') {
                this.logger.warn({ err, path: this.path }, 'failed to release the session lock');
            }
        }
    }

    private static write(path: string, content: LockContent): void {
        const fd = openSync(path, 'wx');
        try {
            writeSync(fd, JSON.stringify(content));
            fsyncSync(fd);
        } finally {
            closeSync(fd);
        }
    }

    private static readOwner(path: string, logger: Logger): LockContent | null {
        try {
            const data = JSON.parse(readFileSync(path, 'utf8')) as Partial<LockContent>;
            if (typeof data.pid !== 'number') return null;
            return {
                pid: data.pid,
                boot: typeof data.boot === 'string' ? data.boot : '',
                startedAt: typeof data.startedAt === 'number' ? data.startedAt : 0,
                instanceId: typeof data.instanceId === 'string' ? data.instanceId : '?'
            };
        } catch (err) {
            logger.warn({ err, path }, 'unreadable session lock - treated as orphaned');
            return null;
        }
    }

    private static isAlive(owner: LockContent): boolean {
        if (owner.pid === process.pid && owner.boot === BOOT_ID) {
            return true;
        }
        try {
            process.kill(owner.pid, 0);
            return true;
        } catch (err) {
            // EPERM means the process exists but belongs to someone else.
            return (err as NodeJS.ErrnoException).code !== 'ESRCH';
        }
    }
}
