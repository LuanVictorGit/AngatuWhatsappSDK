import { BridgeCommandError, ErrorCode } from '../protocol/errors.js';

const WINDOW_MS = 60_000;

/**
 * Sliding-window cap on queries to WhatsApp (number checks, profile pictures,
 * group metadata). An account was suspended for reads, not sends: a screen
 * that fetched 200 avatars on open fired up to 600 probes in a burst, which the
 * server cannot tell apart from scraping. The Java queue already paces reads to
 * the same cap; this is the bridge's own guard, so no caller can turn a loop
 * into a burst. A refusal is better than a hidden wait: it tells the
 * application it is reading too much.
 */
export class ReadBudget {
    private readonly stamps: number[] = [];

    constructor(private readonly perMinute: number) {}

    consume(operation: string, cost = 1): void {
        if (this.perMinute <= 0) {
            return;
        }
        const now = Date.now();
        while (this.stamps.length > 0 && now - this.stamps[0]! >= WINDOW_MS) {
            this.stamps.shift();
        }
        if (this.stamps.length + cost > this.perMinute) {
            const retryInSeconds = Math.ceil((WINDOW_MS - (now - (this.stamps[0] ?? now))) / 1000);
            throw new BridgeCommandError(
                ErrorCode.RATE_LIMITED,
                `read cap reached (${this.perMinute}/min) on '${operation}'; retry in ${retryInSeconds}s`,
                { operation, perMinute: this.perMinute, retryInSeconds }
            );
        }
        for (let i = 0; i < cost; i++) {
            this.stamps.push(now);
        }
    }

    get remaining(): number {
        if (this.perMinute <= 0) {
            return -1;
        }
        const now = Date.now();
        return Math.max(0, this.perMinute - this.stamps.filter((s) => now - s < WINDOW_MS).length);
    }
}
