import type { ErrorPayload } from './messages.js';

/**
 * Stable, machine-readable error codes. These are mirrored one-to-one by
 * com.angatusistemas.whatsapp.exception on the Java side - keep both lists
 * in sync when adding a new code.
 */
export const ErrorCode = {
    INVALID_TOKEN: 'INVALID_TOKEN',
    PROTOCOL_VERSION_MISMATCH: 'PROTOCOL_VERSION_MISMATCH',
    INVALID_MESSAGE: 'INVALID_MESSAGE',
    UNKNOWN_ACTION: 'UNKNOWN_ACTION',
    INSTANCE_NOT_FOUND: 'INSTANCE_NOT_FOUND',
    INSTANCE_ALREADY_EXISTS: 'INSTANCE_ALREADY_EXISTS',
    NOT_CONNECTED: 'NOT_CONNECTED',
    CONNECTION_FAILED: 'CONNECTION_FAILED',
    AUTH_FAILED: 'AUTH_FAILED',
    SEND_FAILED: 'SEND_FAILED',
    TIMEOUT: 'TIMEOUT',
    UNSUPPORTED_FEATURE: 'UNSUPPORTED_FEATURE',
    MEDIA_NOT_FOUND: 'MEDIA_NOT_FOUND',
    INVALID_PAYLOAD: 'INVALID_PAYLOAD',
    INTERNAL_ERROR: 'INTERNAL_ERROR'
} as const;

export type ErrorCodeValue = (typeof ErrorCode)[keyof typeof ErrorCode];

export class BridgeCommandError extends Error {
    readonly code: ErrorCodeValue;
    readonly details?: Record<string, unknown>;

    constructor(code: ErrorCodeValue, message: string, details?: Record<string, unknown>) {
        super(message);
        this.name = 'BridgeCommandError';
        this.code = code;
        this.details = details;
    }

    toPayload(): ErrorPayload {
        return { code: this.code, message: this.message, details: this.details };
    }
}

export function toErrorPayload(err: unknown): ErrorPayload {
    if (err instanceof BridgeCommandError) {
        return err.toPayload();
    }
    if (err instanceof Error) {
        return { code: ErrorCode.INTERNAL_ERROR, message: err.message };
    }
    return { code: ErrorCode.INTERNAL_ERROR, message: String(err) };
}
