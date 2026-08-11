package com.angatusistemas.whatsapp.transport;

/**
 * Why a {@link BridgeConnection} closed. {@code code} follows the WebSocket
 * close-code space: 1000 is a normal closure this side (or the bridge)
 * initiated deliberately; the bridge's own codes 4000-4003 mean it rejected
 * the connection during handshake (see {@code bridge/src/server/WebSocketServer.ts});
 * 4900 means this side gave up after missing heartbeat acknowledgements.
 */
public record DisconnectInfo(int code, String reason, boolean remote) {
}
