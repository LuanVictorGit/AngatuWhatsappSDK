package com.angatusistemas.whatsapp.exception;

import com.angatusistemas.whatsapp.protocol.BridgeErrorCode;

import java.util.Map;

/**
 * Something went wrong at the transport/process level rather than at the
 * WhatsApp-domain level: the bridge process failed to start, the Node
 * runtime could not be located or downloaded, the WebSocket handshake was
 * rejected (bad token, incompatible protocol version), a malformed message
 * was received, or an internal bridge error was reported.
 */
public class BridgeException extends WhatsAppException {

    public BridgeException(String message) {
        super(message);
    }

    public BridgeException(String message, Throwable cause) {
        super(message, cause);
    }

    public BridgeException(String instanceId, BridgeErrorCode errorCode, String message) {
        super(instanceId, errorCode, message);
    }

    public BridgeException(String instanceId, BridgeErrorCode errorCode, String message, Throwable cause,
            Map<String, Object> details) {
        super(instanceId, errorCode, message, cause, details);
    }
}
