import { randomUUID } from 'node:crypto';
import { WebSocketServer, WebSocket } from 'ws';
import type { AddressInfo } from 'node:net';
import type { Logger } from '../util/logger.js';
import { InstanceManager } from '../instance/InstanceManager.js';
import type { EventSink } from '../instance/EventSink.js';
import { dispatch } from '../commands/CommandRouter.js';
import { isHandshake, isHeartbeat, isRequest, isProtocolCompatible, PROTOCOL_VERSION } from '../protocol/messages.js';
import { ErrorCode, toErrorPayload } from '../protocol/errors.js';
import { isValidToken } from './AuthToken.js';

const HANDSHAKE_TIMEOUT_MS = 5_000;
const STALE_CONNECTION_MS = 90_000;
const REAPER_INTERVAL_MS = 15_000;
/**
 * Bytes queued for a client before droppable events start being skipped. A
 * consumer that stops reading must not make this process buffer without bound;
 * messages, responses and state changes are never dropped.
 */
const MAX_BUFFERED_BYTES = 8 * 1024 * 1024;

/**
 * How long the bridge lives with no client at all. A JVM killed with -9 leaves
 * this process behind holding the WhatsApp socket, and the next start would
 * fight it with connectionReplaced. `ANGATU_BRIDGE_IDLE_MS=0` disables it.
 */
const IDLE_SHUTDOWN_MS = (() => {
    const raw = process.env.ANGATU_BRIDGE_IDLE_MS;
    const n = raw === undefined ? NaN : Number.parseInt(raw, 10);
    return Number.isFinite(n) && n >= 0 ? n : 120_000;
})();

interface Connection {
    ws: WebSocket;
    authenticated: boolean;
    lastHeartbeatAt: number;
    handshakeTimer: NodeJS.Timeout;
}

export interface BuildInfo {
    bridgeVersion: string;
    baileysVersion: string;
}

export class BridgeServer implements EventSink {
    readonly instances: InstanceManager;
    onIdleShutdown: (() => void) | null = null;
    private readonly wss: WebSocketServer;
    private readonly connections = new Set<Connection>();
    private readonly reaper: NodeJS.Timeout;
    private idleTimer: NodeJS.Timeout | null = null;
    private closing = false;
    private dropped = 0;

    constructor(
        host: string,
        port: number,
        private readonly token: string,
        private readonly logger: Logger,
        private readonly buildInfo: BuildInfo
    ) {
        this.instances = new InstanceManager(logger, this);
        this.wss = new WebSocketServer({ host, port, maxPayload: 4 * 1024 * 1024, perMessageDeflate: false });
        this.wss.on('connection', (ws) => this.accept(ws));
        this.wss.on('error', (err) => this.logger.error({ err }, 'websocket server error'));
        this.reaper = setInterval(() => this.reap(), REAPER_INTERVAL_MS);
        this.reaper.unref?.();
        this.armIdleShutdown();
    }

    waitUntilListening(): Promise<void> {
        return new Promise((resolve, reject) => {
            this.wss.once('listening', () => resolve());
            this.wss.once('error', reject);
        });
    }

    get port(): number {
        return (this.wss.address() as AddressInfo).port;
    }

    emit(instanceId: string, event: string, data: unknown, droppable = false): void {
        const frame = JSON.stringify({ type: 'event', event, instanceId, data });
        for (const conn of this.connections) {
            if (!conn.authenticated || conn.ws.readyState !== WebSocket.OPEN) continue;
            if (droppable && conn.ws.bufferedAmount > MAX_BUFFERED_BYTES) {
                if (++this.dropped % 1_000 === 1) {
                    this.logger.warn({ dropped: this.dropped }, 'client is not reading fast enough - skipping presence and status events');
                }
                continue;
            }
            conn.ws.send(frame);
        }
    }

    async close(): Promise<void> {
        if (this.closing) return;
        this.closing = true;
        this.disarmIdleShutdown();
        clearInterval(this.reaper);
        await this.instances.shutdownAll();
        for (const conn of this.connections) {
            conn.ws.close(1000, 'bridge shutting down');
        }
        await new Promise<void>((resolve) => this.wss.close(() => resolve()));
        this.logger.info('bridge server closed');
    }

    private accept(ws: WebSocket): void {
        const conn: Connection = {
            ws,
            authenticated: false,
            lastHeartbeatAt: Date.now(),
            handshakeTimer: setTimeout(() => {
                if (!conn.authenticated) {
                    this.sendError(ws, randomUUID(), ErrorCode.TIMEOUT, 'handshake not received in time');
                    ws.close(4000, 'handshake timeout');
                }
            }, HANDSHAKE_TIMEOUT_MS)
        };
        this.connections.add(conn);
        this.disarmIdleShutdown();
        ws.on('message', (raw: Buffer) => void this.receive(conn, raw));
        ws.on('close', () => {
            clearTimeout(conn.handshakeTimer);
            this.connections.delete(conn);
            if (this.connections.size === 0) this.armIdleShutdown();
        });
        ws.on('error', (err) => this.logger.warn({ err }, 'client connection error'));
    }

    private async receive(conn: Connection, raw: Buffer): Promise<void> {
        let msg: Record<string, unknown>;
        try {
            msg = JSON.parse(raw.toString('utf8'));
        } catch {
            this.sendError(conn.ws, randomUUID(), ErrorCode.INVALID_MESSAGE, 'payload is not valid JSON');
            return;
        }
        const id = typeof msg.id === 'string' ? msg.id : randomUUID();
        if (!conn.authenticated) {
            this.handshake(conn, msg, id);
            return;
        }
        // Any frame proves the client is alive, not only heartbeats.
        conn.lastHeartbeatAt = Date.now();
        if (isHeartbeat(msg)) {
            this.send(conn.ws, { type: 'heartbeat_ack', id });
        } else if (isRequest(msg)) {
            await this.request(conn, msg, id);
        } else {
            this.sendError(conn.ws, id, ErrorCode.INVALID_MESSAGE, `unexpected message type: ${String(msg.type)}`);
        }
    }

    private handshake(conn: Connection, msg: Record<string, unknown>, id: string): void {
        if (!isHandshake(msg)) {
            this.sendError(conn.ws, id, ErrorCode.INVALID_MESSAGE, 'first message must be a handshake');
            conn.ws.close(4001, 'handshake expected');
            return;
        }
        const version = typeof msg.protocolVersion === 'string' ? msg.protocolVersion : '';
        if (!isProtocolCompatible(version)) {
            this.sendError(conn.ws, id, ErrorCode.PROTOCOL_VERSION_MISMATCH, `protocol mismatch: client=${version} bridge=${PROTOCOL_VERSION}`);
            conn.ws.close(4002, 'protocol version mismatch');
            return;
        }
        if (!isValidToken(this.token, msg.token)) {
            this.sendError(conn.ws, id, ErrorCode.INVALID_TOKEN, 'invalid token');
            conn.ws.close(4003, 'invalid token');
            return;
        }
        conn.authenticated = true;
        conn.lastHeartbeatAt = Date.now();
        clearTimeout(conn.handshakeTimer);
        this.send(conn.ws, {
            type: 'handshake_ack',
            id,
            protocolVersion: PROTOCOL_VERSION,
            bridgeVersion: this.buildInfo.bridgeVersion,
            baileysVersion: this.buildInfo.baileysVersion,
            pid: process.pid
        });
    }

    private async request(conn: Connection, msg: Record<string, unknown>, id: string): Promise<void> {
        const action = typeof msg.action === 'string' ? msg.action : '';
        const instanceId = typeof msg.instanceId === 'string' ? msg.instanceId : undefined;
        const payload = (msg.payload as Record<string, unknown>) ?? {};
        if (action === 'shutdown') {
            this.send(conn.ws, { type: 'response', id, success: true, data: {} });
            setTimeout(() => void this.close().finally(() => this.onIdleShutdown?.()), 100);
            return;
        }
        try {
            const data = await dispatch(this.instances, action, instanceId, payload);
            this.send(conn.ws, { type: 'response', id, success: true, data: data ?? {} });
        } catch (err) {
            if ((err as { name?: string })?.name !== 'BridgeCommandError') {
                this.logger.error({ err, action, instanceId }, 'command failed unexpectedly');
            }
            this.send(conn.ws, { type: 'response', id, success: false, error: toErrorPayload(err) });
        }
    }

    private send(ws: WebSocket, frame: unknown): void {
        if (ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify(frame));
        }
    }

    private sendError(ws: WebSocket, id: string, code: string, message: string): void {
        this.send(ws, { type: 'error', id, error: { code, message } });
    }

    private reap(): void {
        const now = Date.now();
        for (const conn of this.connections) {
            if (conn.authenticated && now - conn.lastHeartbeatAt > STALE_CONNECTION_MS) {
                this.logger.warn('closing a client with no recent traffic');
                conn.ws.terminate();
                this.connections.delete(conn);
            }
        }
        if (this.connections.size === 0) this.armIdleShutdown();
    }

    private armIdleShutdown(): void {
        if (IDLE_SHUTDOWN_MS === 0 || this.closing || this.idleTimer) return;
        this.idleTimer = setTimeout(() => {
            this.idleTimer = null;
            if (this.closing || this.connections.size > 0) return;
            this.logger.warn({ idleMs: IDLE_SHUTDOWN_MS }, 'no client for too long - shutting down to avoid an orphaned bridge');
            void this.close().finally(() => this.onIdleShutdown?.());
        }, IDLE_SHUTDOWN_MS);
        this.idleTimer.unref?.();
    }

    private disarmIdleShutdown(): void {
        if (this.idleTimer) {
            clearTimeout(this.idleTimer);
            this.idleTimer = null;
        }
    }
}
