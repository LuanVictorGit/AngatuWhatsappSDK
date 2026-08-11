import { randomUUID } from 'node:crypto';
import { WebSocketServer as WSServer, WebSocket } from 'ws';
import type { AddressInfo } from 'node:net';
import type { Logger } from '../util/logger.js';
import { InstanceManager } from '../instance/InstanceManager.js';
import type { EventSink } from '../instance/EventSink.js';
import { dispatch } from '../commands/CommandRouter.js';
import { isHandshake, isHeartbeat, isRequest, isProtocolCompatible, PROTOCOL_VERSION } from '../protocol/messages.js';
import { BridgeCommandError, ErrorCode, toErrorPayload } from '../protocol/errors.js';
import { isValidToken } from './AuthToken.js';

const HANDSHAKE_TIMEOUT_MS = 5_000;
const STALE_CONNECTION_MS = 90_000;
const REAPER_INTERVAL_MS = 15_000;

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
    private readonly wss: WSServer;
    private readonly connections = new Set<Connection>();
    private readonly reaperTimer: NodeJS.Timeout;
    readonly instanceManager: InstanceManager;
    private closing = false;

    constructor(
        host: string,
        port: number,
        private readonly expectedToken: string,
        private readonly logger: Logger,
        private readonly buildInfo: BuildInfo
    ) {
        this.instanceManager = new InstanceManager(logger, this);
        this.wss = new WSServer({ host, port, maxPayload: 16 * 1024 * 1024 });
        this.wss.on('connection', (ws) => this.handleConnection(ws));
        this.wss.on('error', (err) => this.logger.error({ err }, 'websocket server error'));
        this.reaperTimer = setInterval(() => this.reapStaleConnections(), REAPER_INTERVAL_MS);
    }

    /** Resolves once the OS has actually bound the listening socket. */
    waitUntilListening(): Promise<void> {
        return new Promise((resolvePromise, reject) => {
            this.wss.once('listening', () => resolvePromise());
            this.wss.once('error', reject);
        });
    }

    get actualPort(): number {
        return (this.wss.address() as AddressInfo).port;
    }

    private handleConnection(ws: WebSocket): void {
        const conn: Connection = {
            ws,
            authenticated: false,
            lastHeartbeatAt: Date.now(),
            handshakeTimer: setTimeout(() => {
                if (!conn.authenticated) {
                    this.sendError(ws, randomUUID(), ErrorCode.TIMEOUT, 'handshake nao recebido a tempo');
                    ws.close(4000, 'handshake timeout');
                }
            }, HANDSHAKE_TIMEOUT_MS)
        };
        this.connections.add(conn);

        ws.on('message', (raw: Buffer) => {
            void this.handleMessage(conn, raw);
        });

        ws.on('close', () => {
            clearTimeout(conn.handshakeTimer);
            this.connections.delete(conn);
        });

        ws.on('error', (err) => this.logger.warn({ err }, 'connection error'));
    }

    private async handleMessage(conn: Connection, raw: Buffer): Promise<void> {
        let msg: Record<string, unknown>;
        try {
            msg = JSON.parse(raw.toString('utf8'));
        } catch {
            this.sendError(conn.ws, randomUUID(), ErrorCode.INVALID_MESSAGE, 'payload nao e JSON valido');
            return;
        }

        const id = typeof msg.id === 'string' ? msg.id : randomUUID();

        if (!conn.authenticated) {
            this.handleHandshake(conn, msg, id);
            return;
        }

        if (isHeartbeat(msg as { type?: string })) {
            conn.lastHeartbeatAt = Date.now();
            this.send(conn.ws, { type: 'heartbeat_ack', id });
            return;
        }

        if (isRequest(msg as { type?: string })) {
            await this.handleRequest(conn, msg, id);
            return;
        }

        this.sendError(conn.ws, id, ErrorCode.INVALID_MESSAGE, `Tipo de mensagem inesperado apos handshake: ${String(msg.type)}`);
    }

    private handleHandshake(conn: Connection, msg: Record<string, unknown>, id: string): void {
        if (!isHandshake(msg as { type?: string })) {
            this.sendError(conn.ws, id, ErrorCode.INVALID_MESSAGE, 'primeira mensagem da conexao deve ser handshake');
            conn.ws.close(4001, 'handshake esperado');
            return;
        }

        const protocolVersion = typeof msg.protocolVersion === 'string' ? msg.protocolVersion : '';
        if (!isProtocolCompatible(protocolVersion)) {
            this.sendError(
                conn.ws,
                id,
                ErrorCode.PROTOCOL_VERSION_MISMATCH,
                `Versao de protocolo incompativel: cliente=${protocolVersion} bridge=${PROTOCOL_VERSION}`
            );
            conn.ws.close(4002, 'protocol version mismatch');
            return;
        }

        if (!isValidToken(this.expectedToken, msg.token)) {
            this.sendError(conn.ws, id, ErrorCode.INVALID_TOKEN, 'token de autenticacao invalido');
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
        this.logger.info('client authenticated');
    }

    private async handleRequest(conn: Connection, msg: Record<string, unknown>, id: string): Promise<void> {
        const action = typeof msg.action === 'string' ? msg.action : '';
        const instanceId = typeof msg.instanceId === 'string' ? msg.instanceId : undefined;
        const payload = (msg.payload as Record<string, unknown>) ?? {};

        if (action === 'shutdown') {
            this.send(conn.ws, { type: 'response', id, success: true, data: {} });
            setTimeout(() => void this.close(), 100);
            return;
        }

        try {
            const data = await dispatch(this.instanceManager, action, instanceId, payload);
            this.send(conn.ws, { type: 'response', id, success: true, data: data ?? {} });
        } catch (err) {
            if (!(err instanceof BridgeCommandError)) {
                this.logger.error({ err, action, instanceId }, 'unhandled error while executing command');
            }
            this.send(conn.ws, { type: 'response', id, success: false, error: toErrorPayload(err) });
        }
    }

    /** EventSink implementation - fan out to every authenticated connection. */
    emit(instanceId: string, event: string, data: unknown): void {
        const envelope = { type: 'event', id: randomUUID(), event, instanceId, data };
        for (const conn of this.connections) {
            if (conn.authenticated) {
                this.send(conn.ws, envelope);
            }
        }
    }

    private send(ws: WebSocket, obj: unknown): void {
        if (ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify(obj));
        }
    }

    private sendError(ws: WebSocket, id: string, code: string, message: string): void {
        this.send(ws, { type: 'error', id, error: { code, message } });
    }

    private reapStaleConnections(): void {
        const now = Date.now();
        for (const conn of this.connections) {
            if (conn.authenticated && now - conn.lastHeartbeatAt > STALE_CONNECTION_MS) {
                this.logger.warn('closing connection with no recent heartbeat');
                conn.ws.terminate();
                this.connections.delete(conn);
            }
        }
    }

    async close(): Promise<void> {
        if (this.closing) return;
        this.closing = true;
        clearInterval(this.reaperTimer);
        await this.instanceManager.shutdownAll();
        for (const conn of this.connections) {
            conn.ws.close(1000, 'bridge shutting down');
        }
        await new Promise<void>((resolvePromise) => this.wss.close(() => resolvePromise()));
        this.logger.info('bridge server closed');
    }
}
