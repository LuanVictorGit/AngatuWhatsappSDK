import { open, mkdir, readFile, readdir, rename, rm, unlink, stat } from 'node:fs/promises';
import { join } from 'node:path';
import {
    initAuthCreds,
    BufferJSON,
    proto,
    type AuthenticationCreds,
    type AuthenticationState,
    type SignalDataTypeMap
} from 'baileys';
import type { Logger } from '../util/logger.js';

/** Session-directory entries that are not credentials and are never wiped. */
const NOT_CREDENTIALS = new Set(['.lock']);

const TMP = '.tmp';
const CREDENTIALS = 'creds.json';
/** Copy of the credentials taken at every successful connection, used when creds.json is lost. */
const BACKUP = 'creds.backup.json';

export interface DurableAuthState {
    state: AuthenticationState;
    saveCreds: () => Promise<void>;
    backupCreds: () => Promise<void>;
}

/**
 * Baileys auth state written durably, with nothing cached in memory beyond the
 * credentials object itself.
 *
 * Baileys' own `useMultiFileAuthState` writes straight over the final file: a
 * crash mid-write leaves `creds.json` truncated, its reader swallows the parse
 * error and `initAuthCreds()` mints a brand new identity - the operator only
 * sees "asked for a QR code out of nowhere". Here every file is written to a
 * temporary, fsynced and renamed over the final one, so it holds either the old
 * content or the new one, never half of it. The on-disk format is identical to
 * Baileys' (same names, same BufferJSON), so existing sessions keep working.
 *
 * Signal keys are read from disk on demand instead of being cached: CPU and
 * I/O on each decrypt instead of every key of every contact in the heap.
 */
export async function useDurableAuthState(folder: string, logger: Logger): Promise<DurableAuthState> {
    const info = await stat(folder).catch(() => null);
    if (info && !info.isDirectory()) {
        throw new Error(`not a directory: ${folder}`);
    }
    if (!info) {
        await mkdir(folder, { recursive: true });
    }

    const locks = new Map<string, Promise<unknown>>();

    // Serialises operations on one file inside this process; SessionLock covers other processes.
    const withLock = <T>(path: string, task: () => Promise<T>): Promise<T> => {
        const previous = locks.get(path) ?? Promise.resolve();
        // then(task, task): one failed write must not wedge that file forever.
        const current = previous.then(task, task);
        const settled = current.then(
            () => undefined,
            () => undefined
        );
        locks.set(path, settled);
        void settled.then(() => {
            if (locks.get(path) === settled) {
                locks.delete(path);
            }
        });
        return current;
    };

    const safeName = (file: string): string => file?.replace(/\//g, '__')?.replace(/:/g, '-');

    const write = async (data: unknown, file: string): Promise<void> => {
        const path = join(folder, safeName(file));
        await withLock(path, async () => {
            const temporary = path + TMP;
            const content = JSON.stringify(data, BufferJSON.replacer);
            const fd = await open(temporary, 'w');
            try {
                await fd.writeFile(content, 'utf8');
                // Without fsync a crash right after the rename can leave an empty file in place of the old one.
                await fd.sync();
            } finally {
                await fd.close();
            }
            await rename(temporary, path);
        });
    };

    const read = async (file: string): Promise<unknown> => {
        const path = join(folder, safeName(file));
        return withLock(path, async () => {
            try {
                return JSON.parse(await readFile(path, { encoding: 'utf-8' }), BufferJSON.reviver);
            } catch (err) {
                if ((err as NodeJS.ErrnoException).code !== 'ENOENT') {
                    logger.error({ err, file: safeName(file) }, 'unreadable auth file - treated as missing');
                }
                return null;
            }
        });
    };

    const remove = async (file: string): Promise<void> => {
        const path = join(folder, safeName(file));
        await withLock(path, async () => {
            await unlink(path).catch(() => undefined);
        });
    };

    await removeLeftoverTemporaries(folder, logger);

    let creds = (await read(CREDENTIALS)) as AuthenticationCreds | null;
    if (!creds) {
        creds = (await read(BACKUP)) as AuthenticationCreds | null;
        if (creds) {
            logger.warn('credentials file missing or unreadable - restored from the last good backup');
        }
    }
    creds ??= initAuthCreds();
    discardUnfinishedPairing(creds, logger);

    return {
        state: {
            creds,
            keys: {
                get: async (type, ids) => {
                    const data: { [id: string]: SignalDataTypeMap[typeof type] } = {};
                    await Promise.all(
                        ids.map(async (id) => {
                            let value = await read(`${type}-${id}.json`);
                            if (type === 'app-state-sync-key' && value) {
                                value = proto.Message.AppStateSyncKeyData.fromObject(value as never);
                            }
                            data[id] = value as SignalDataTypeMap[typeof type];
                        })
                    );
                    return data;
                },
                set: async (data) => {
                    const tasks: Promise<void>[] = [];
                    for (const category in data) {
                        const group = data[category as keyof typeof data];
                        for (const id in group) {
                            const value = (group as Record<string, unknown>)[id];
                            const file = `${category}-${id}.json`;
                            tasks.push(value ? write(value, file) : remove(file));
                        }
                    }
                    await Promise.all(tasks);
                }
            }
        },
        saveCreds: async () => write(creds, CREDENTIALS),
        backupCreds: async () => {
            if (isPaired(creds)) {
                await write(creds, BACKUP);
            }
        }
    };
}

/** Paired means WhatsApp issued the signed device account; an identity alone is a pairing in progress. */
export function isPaired(creds: AuthenticationCreds): boolean {
    return Boolean(creds.me?.id && creds.account);
}

/**
 * A pairing code request stores an identity before the phone confirms it. Loaded back as is, that
 * half identity makes Baileys send a login instead of a registration, WhatsApp answers 401 and the
 * session is wiped - which is how a restart, or the socket renewal while the operator is still
 * typing the code, turned into "logged out by itself". Dropping it makes the next socket register
 * again; an in-progress pairing code is re-registered on it.
 */
function discardUnfinishedPairing(creds: AuthenticationCreds, logger: Logger): void {
    if (creds.me?.id && !creds.account) {
        delete (creds as Partial<AuthenticationCreds>).me;
        delete (creds as Partial<AuthenticationCreds>).pairingCode;
        logger.info('unfinished pairing found in the credentials - starting the login again');
    }
}

async function removeLeftoverTemporaries(folder: string, logger: Logger): Promise<void> {
    try {
        const leftovers = (await readdir(folder)).filter((name) => name.endsWith(TMP));
        for (const name of leftovers) {
            await unlink(join(folder, name)).catch(() => undefined);
        }
        if (leftovers.length > 0) {
            logger.warn({ count: leftovers.length }, 'removed leftovers of an interrupted write from the session directory');
        }
    } catch {
        // freshly created or unreadable directory: nothing to clean
    }
}

/**
 * Deletes the credentials of a session directory while keeping the directory
 * and its `.lock`: removing the whole folder would drop the lock this process
 * still holds, letting another process take the account mid re-pairing.
 */
export async function wipeCredentials(folder: string, logger: Logger): Promise<void> {
    let names: string[];
    try {
        names = await readdir(folder);
    } catch (err) {
        if ((err as NodeJS.ErrnoException).code !== 'ENOENT') {
            logger.error({ err, folder }, 'failed to list the session directory for wiping');
        }
        return;
    }
    let removed = 0;
    for (const name of names) {
        if (NOT_CREDENTIALS.has(name)) {
            continue;
        }
        try {
            await rm(join(folder, name), { recursive: true, force: true });
            removed++;
        } catch (err) {
            logger.warn({ err, file: name }, 'could not delete a credential file');
        }
    }
    logger.info({ removed }, 'session credentials removed (lock and directory kept)');
}
