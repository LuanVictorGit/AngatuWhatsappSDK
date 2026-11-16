import { fetchLatestWaWebVersion, fetchLatestBaileysVersion } from 'baileys';
import type { WAVersion } from 'baileys';
import type { Logger } from '../util/logger.js';

/**
 * Which WhatsApp Web version to announce in the handshake.
 *
 * Baileys ships a frozen version that ages with the package, and WhatsApp
 * refuses to pair an old client in a misleading way: the QR code and pairing
 * code render fine, then the phone says "could not link". This lookup must
 * never break the connection itself, so: `null` means "unknown" and the caller
 * omits the key (passing `undefined` would override Baileys' default with
 * nothing); the network answer is validated, because both Baileys helpers
 * report `isLatest: true` without checking the body; and the whole lookup has a
 * deadline.
 */
const VALID_FOR_MS = 6 * 60 * 60 * 1000;
const PER_QUERY_TIMEOUT_MS = 4_000;
const TOTAL_TIMEOUT_MS = 9_000;

let cached: { version: WAVersion; at: number } | null = null;
let inFlight: Promise<WAVersion | null> | null = null;

export async function resolveWhatsAppVersion(logger: Logger): Promise<WAVersion | null> {
    try {
        if (process.env.ANGATU_WA_VERSION_OFF === '1') {
            return null;
        }
        if (cached && Date.now() - cached.at < VALID_FOR_MS) {
            return cached.version;
        }
        inFlight ??= lookup(logger).finally(() => {
            inFlight = null;
        });
        return await Promise.race([inFlight, expireAfter(TOTAL_TIMEOUT_MS)]);
    } catch (err) {
        logger.warn({ err }, 'could not resolve the WhatsApp Web version; using the bundled one');
        return null;
    }
}

export function isValidVersion(v: unknown): v is WAVersion {
    return Array.isArray(v) && v.length === 3 && v.every((n) => typeof n === 'number' && Number.isInteger(n) && n >= 0);
}

async function lookup(logger: Logger): Promise<WAVersion | null> {
    const fromWhatsApp = await fetchLatestWaWebVersion({ signal: AbortSignal.timeout(PER_QUERY_TIMEOUT_MS) }).catch(() => null);
    if (fromWhatsApp?.isLatest && isValidVersion(fromWhatsApp.version)) {
        return keep(fromWhatsApp.version, 'web.whatsapp.com', logger);
    }
    const fromBaileys = await fetchLatestBaileysVersion({ signal: AbortSignal.timeout(PER_QUERY_TIMEOUT_MS) }).catch(() => null);
    if (fromBaileys?.isLatest && isValidVersion(fromBaileys.version)) {
        return keep(fromBaileys.version, 'baileys repository', logger);
    }
    logger.warn('WhatsApp Web version unknown; using the bundled one - if pairing fails, this is why');
    return null;
}

function keep(version: WAVersion, source: string, logger: Logger): WAVersion {
    cached = { version, at: Date.now() };
    logger.info({ version: version.join('.'), source }, 'WhatsApp Web version resolved');
    return version;
}

function expireAfter(ms: number): Promise<null> {
    return new Promise((resolve) => {
        const timer = setTimeout(() => resolve(null), ms);
        timer.unref?.();
    });
}
