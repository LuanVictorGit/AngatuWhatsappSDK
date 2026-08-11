import { timingSafeEqual } from 'node:crypto';

const ENV_VAR = 'ANGATU_BRIDGE_TOKEN';

/**
 * The shared secret is passed exclusively through an environment variable,
 * never a CLI argument, so it does not appear in `ps`/Task Manager process
 * listings and is never written to a log line (see logger.ts redact rules).
 */
export function readExpectedToken(): string {
    const token = process.env[ENV_VAR];
    if (!token || token.length < 16) {
        throw new Error(
            `${ENV_VAR} must be set to a securely generated token (>=16 chars) before starting the bridge`
        );
    }
    return token;
}

export function isValidToken(expected: string, provided: unknown): boolean {
    if (typeof provided !== 'string' || provided.length === 0) {
        return false;
    }
    const expectedBuf = Buffer.from(expected, 'utf8');
    const providedBuf = Buffer.from(provided, 'utf8');
    if (expectedBuf.length !== providedBuf.length) {
        // Still run a comparison of equal-length buffers so the failure
        // path takes constant time regardless of the length mismatch.
        timingSafeEqual(expectedBuf, expectedBuf);
        return false;
    }
    return timingSafeEqual(expectedBuf, providedBuf);
}
