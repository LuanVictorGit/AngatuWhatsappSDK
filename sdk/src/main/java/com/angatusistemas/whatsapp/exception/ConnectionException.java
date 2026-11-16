package com.angatusistemas.whatsapp.exception;

/**
 * The account or the bridge is not connected, or the connection could not be established.
 *
 * @author Angatu Sistemas
 */
public final class ConnectionException extends WhatsAppException {

    public ConnectionException(String instanceId, BridgeErrorCode errorCode, String message) {
        super(instanceId, errorCode, message);
    }

    public ConnectionException(String instanceId, BridgeErrorCode errorCode, String message, Throwable cause) {
        super(instanceId, errorCode, message, cause);
    }
}
