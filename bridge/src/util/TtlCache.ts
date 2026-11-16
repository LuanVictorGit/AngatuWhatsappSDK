import type { CacheStore } from 'baileys';

/**
 * Bounded key/value cache with per-entry expiry and no timer.
 *
 * Baileys creates its retry, device, call and placeholder caches with
 * `@cacheable/node-cache`, which arms a `setInterval` per cache that is never
 * cleared when the socket ends. Every reconnect therefore leaked four caches,
 * their contents and four live intervals. One instance of this class is
 * created per WhatsApp account and handed to every socket that account opens,
 * so reconnecting allocates nothing new.
 *
 * Expiry is lazy (checked on read, and in bulk when the cache is full): CPU on
 * access instead of memory and a timer kept alive forever.
 */
export class TtlCache implements CacheStore {
    private readonly entries = new Map<string, { value: unknown; expiresAt: number }>();

    constructor(
        private readonly ttlMs: number,
        private readonly maxEntries: number
    ) {}

    get<T>(key: string): T | undefined {
        const entry = this.entries.get(key);
        if (!entry) {
            return undefined;
        }
        if (Date.now() >= entry.expiresAt) {
            this.entries.delete(key);
            return undefined;
        }
        return entry.value as T;
    }

    set<T>(key: string, value: T): void {
        this.entries.delete(key);
        if (this.entries.size >= this.maxEntries) {
            this.evict();
        }
        this.entries.set(key, { value, expiresAt: Date.now() + this.ttlMs });
    }

    del(key: string): void {
        this.entries.delete(key);
    }

    flushAll(): void {
        this.entries.clear();
    }

    get size(): number {
        return this.entries.size;
    }

    private evict(): void {
        const now = Date.now();
        for (const [key, entry] of this.entries) {
            if (now >= entry.expiresAt) {
                this.entries.delete(key);
            }
        }
        // Still full: drop the oldest insertions, which a Map iterates first.
        const excess = this.entries.size - this.maxEntries + 1;
        if (excess > 0) {
            let removed = 0;
            for (const key of this.entries.keys()) {
                if (removed++ >= excess) break;
                this.entries.delete(key);
            }
        }
    }
}

/**
 * Insertion-ordered map with a hard size cap: the oldest key leaves when a new
 * one arrives at a full map. Used for indexes whose loss only costs a lookup.
 */
export class BoundedMap<K, V> {
    private readonly entries = new Map<K, V>();

    constructor(private readonly maxEntries: number) {}

    get(key: K): V | undefined {
        return this.entries.get(key);
    }

    has(key: K): boolean {
        return this.entries.has(key);
    }

    set(key: K, value: V): void {
        this.entries.delete(key);
        if (this.entries.size >= this.maxEntries) {
            const oldest = this.entries.keys().next();
            if (!oldest.done) {
                this.entries.delete(oldest.value);
            }
        }
        this.entries.set(key, value);
    }

    delete(key: K): void {
        this.entries.delete(key);
    }

    clear(): void {
        this.entries.clear();
    }

    get size(): number {
        return this.entries.size;
    }
}
