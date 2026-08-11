package com.angatusistemas.whatsapp.exception;

import com.angatusistemas.whatsapp.protocol.BridgeErrorCode;

import java.util.Map;

/** The instance is not connected, or the connection attempt itself failed. */
public class ConnectionException extends WhatsAppException {

    public ConnectionException(String message) {
        super(message);
    }

    public ConnectionException(String instanceId, BridgeErrorCode errorCode, String message) {
        super(instanceId, errorCode, message);
    }

    public ConnectionException(String instanceId, BridgeErrorCode errorCode, String message, Throwable cause,
            Map<String, Object> details) {
        super(instanceId, errorCode, message, cause, details);
    }
}
