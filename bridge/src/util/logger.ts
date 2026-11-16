// Named import: pino's default export does not resolve to a callable type under NodeNext.
import { pino, type LogFn } from 'pino';

/**
 * Structured JSON logging to stdout, one record per line. The Java side reads
 * this stream and re-emits it, so the shape stays pino's default. No transport
 * is configured: pino transports load a worker by dynamic path, which does not
 * survive bundling into a single file.
 */
const level = process.env.ANGATU_BRIDGE_LOG_LEVEL ?? 'info';

const REDACT = {
    paths: ['token', '*.token', 'creds', '*.creds', 'authState', '*.authState'],
    censor: '[redacted]'
};

export const rootLogger = pino({ level, redact: REDACT, timestamp: pino.stdTimeFunctions.isoTime });

export function childLogger(bindings: Record<string, unknown>) {
    return rootLogger.child(bindings);
}

export type Logger = ReturnType<typeof childLogger>;

/**
 * Lines Baileys logs at error level that are the protocol working as designed:
 * a group message from a device we have no Signal session with yet, answered
 * with a retry receipt. In a busy group they bury every useful line, so each
 * one is explained once and its repeats are only counted.
 */
const PROTOCOL_NOISE: ReadonlyArray<{ fragment: string; explanation: string }> = [
    {
        fragment: 'failed to decrypt message',
        explanation:
            'group message we could not decrypt (no Signal session with the sending device yet); ' +
            'Baileys already asked the sender to resend. Normal protocol behaviour. Repeats are counted, ' +
            'not logged (ANGATU_BRIDGE_LOG_LEVEL=debug shows them).'
    },
    {
        fragment: 'sent retry receipt',
        explanation: 'retry receipt sent for a message we could not decrypt. Repeats are counted, not logged.'
    }
];

const SHOWS_EVERYTHING = level === 'debug' || level === 'trace';

const noiseCounters = new Map<string, Map<string, number>>();

export function protocolNoise(instanceId: string): Record<string, number> {
    const counters = noiseCounters.get(instanceId);
    return counters ? Object.fromEntries(counters) : {};
}

export function forgetProtocolNoise(instanceId: string): void {
    noiseCounters.delete(instanceId);
}

function countNoise(instanceId: string, fragment: string): number {
    let counters = noiseCounters.get(instanceId);
    if (!counters) {
        counters = new Map();
        noiseCounters.set(instanceId, counters);
    }
    const total = (counters.get(fragment) ?? 0) + 1;
    counters.set(fragment, total);
    return total;
}

/**
 * Logger handed to Baileys for one account. A separate root (not a child)
 * because the noise filter lives in `hooks.logMethod`, which only a root has.
 */
export function baileysLogger(instanceId: string) {
    const explained = new Set<string>();
    return pino({
        level,
        redact: REDACT,
        timestamp: pino.stdTimeFunctions.isoTime,
        hooks: {
            logMethod(args: [msg: string, ...rest: unknown[]], method: LogFn) {
                if (SHOWS_EVERYTHING) {
                    method.apply(this, args);
                    return;
                }
                const text = (args as unknown[]).find((a) => typeof a === 'string') as string | undefined;
                const noise = text ? PROTOCOL_NOISE.find((n) => text.includes(n.fragment)) : undefined;
                if (!noise) {
                    method.apply(this, args);
                    return;
                }
                const total = countNoise(instanceId, noise.fragment);
                if (!explained.has(noise.fragment)) {
                    explained.add(noise.fragment);
                    method.apply(this, [noise.explanation]);
                } else if (total % 500 === 0) {
                    // An escalation of decrypt failures is the early sign of a degrading Signal session.
                    method.apply(this, [
                        { occurrences: total, fragment: noise.fragment },
                        'protocol noise repeating often - check get_status if the number is unstable'
                    ] as never);
                }
            }
        }
    }).child({ instanceId, component: 'baileys' });
}
