/**
 * Wire protocol shared conceptually with the Java side
 * (com.angatusistemas.whatsapp.protocol). Any change here that is not
 * backwards compatible MUST bump PROTOCOL_VERSION's major component.
 */

export const PROTOCOL_VERSION = '1.0';

export type EnvelopeType =
    | 'handshake'
    | 'handshake_ack'
    | 'heartbeat'
    | 'heartbeat_ack'
    | 'request'
    | 'response'
    | 'event'
    | 'error';

export interface HandshakeMessage {
    type: 'handshake';
    id: string;
    protocolVersion: string;
    token: string;
}

export interface HandshakeAckMessage {
    type: 'handshake_ack';
    id: string;
    protocolVersion: string;
    bridgeVersion: string;
    baileysVersion: string;
    pid: number;
}

export interface HeartbeatMessage {
    type: 'heartbeat';
    id: string;
}

export interface HeartbeatAckMessage {
    type: 'heartbeat_ack';
    id: string;
}

export interface RequestMessage<P = unknown> {
    type: 'request';
    id: string;
    action: string;
    instanceId?: string;
    payload?: P;
}

export interface ResponseMessage<D = unknown> {
    type: 'response';
    id: string;
    success: boolean;
    data?: D;
    error?: ErrorPayload;
}

export interface EventMessage<D = unknown> {
    type: 'event';
    id: string;
    event: string;
    instanceId?: string;
    data: D;
}

export interface ProtocolErrorMessage {
    type: 'error';
    id: string;
    error: ErrorPayload;
}

export interface ErrorPayload {
    code: string;
    message: string;
    details?: Record<string, unknown>;
}

export type InboundMessage = HandshakeMessage | HeartbeatMessage | RequestMessage;

export type OutboundMessage =
    | HandshakeAckMessage
    | HeartbeatAckMessage
    | ResponseMessage
    | EventMessage
    | ProtocolErrorMessage;

export function isHandshake(msg: { type?: string }): msg is HandshakeMessage {
    return msg.type === 'handshake';
}

export function isHeartbeat(msg: { type?: string }): msg is HeartbeatMessage {
    return msg.type === 'heartbeat';
}

export function isRequest(msg: { type?: string }): msg is RequestMessage {
    return msg.type === 'request';
}

/** Only the major component needs to match; minor additions must stay additive/optional. */
export function isProtocolCompatible(remoteVersion: string): boolean {
    const remoteMajor = remoteVersion.split('.')[0];
    const localMajor = PROTOCOL_VERSION.split('.')[0];
    return remoteMajor === localMajor;
}
