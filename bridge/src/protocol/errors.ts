import type { ErrorPayload } from './messages.js';

/** Machine-readable error codes, mirrored one to one by BridgeErrorCode on the Java side. */
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
    RATE_LIMITED: 'RATE_LIMITED',
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
}

export function toErrorPayload(err: unknown): ErrorPayload {
    if (err instanceof BridgeCommandError) {
        return { code: err.code, message: err.message, details: err.details };
    }
    if (err instanceof Error) {
        return { code: ErrorCode.INTERNAL_ERROR, message: err.message };
    }
    return { code: ErrorCode.INTERNAL_ERROR, message: String(err) };
}
