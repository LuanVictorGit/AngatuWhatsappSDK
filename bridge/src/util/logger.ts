// Named import (not default): pino's CJS->ESM default-export interop under
// "moduleResolution": "NodeNext" resolves to a non-callable namespace type
// with a plain default import. pino explicitly publishes `pino` as a named
// export too, which types and bundles correctly.
import { pino } from 'pino';

/**
 * Structured JSON logger written synchronously to stdout (one line per
 * record). The Java side's BridgeLogForwarder reads this stream line by
 * line and re-emits it through SLF4J, so the shape here (level, time,
 * msg, plus bindings) is deliberately kept pino-default rather than
 * customized - it is machine-parsed on the other end.
 *
 * No `transport` is configured on purpose: pino transports run in a
 * worker thread loaded via a dynamic path that does not survive being
 * bundled into a single file by esbuild.
 */
const level = process.env.ANGATU_BRIDGE_LOG_LEVEL ?? 'info';

export const rootLogger = pino({
    level,
    // Never let the WebSocket auth token, or full session credentials,
    // reach a log line even if a caller accidentally logs an object
    // that contains one of these keys.
    redact: {
        paths: ['token', '*.token', 'creds', '*.creds', 'authState', '*.authState'],
        censor: '[redacted]'
    },
    timestamp: pino.stdTimeFunctions.isoTime
});

export function childLogger(bindings: Record<string, unknown>) {
    return rootLogger.child(bindings);
}

export type Logger = ReturnType<typeof childLogger>;
