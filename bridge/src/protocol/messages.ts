/**
 * Wire protocol shared with the Java side (com.angatusistemas.whatsapp.bridge).
 * A change that is not backwards compatible bumps the major component.
 */
export const PROTOCOL_VERSION = '2.0';

export interface ErrorPayload {
    code: string;
    message: string;
    details?: Record<string, unknown>;
}

export function isHandshake(msg: { type?: string }): boolean {
    return msg.type === 'handshake';
}

export function isHeartbeat(msg: { type?: string }): boolean {
    return msg.type === 'heartbeat';
}

export function isRequest(msg: { type?: string }): boolean {
    return msg.type === 'request';
}

/** Only the major component has to match; minor additions stay optional. */
export function isProtocolCompatible(remoteVersion: string): boolean {
    return remoteVersion.split('.')[0] === PROTOCOL_VERSION.split('.')[0];
}
