package com.angatusistemas.whatsapp.exception;

/**
 * The local bridge process or its protocol failed: start-up, crash or malformed frame.
 *
 * @author Angatu Sistemas
 */
public final class BridgeException extends WhatsAppException {

    public BridgeException(String instanceId, BridgeErrorCode errorCode, String message) {
        super(instanceId, errorCode, message);
    }

    public BridgeException(String instanceId, BridgeErrorCode errorCode, String message, Throwable cause) {
        super(instanceId, errorCode, message, cause);
    }
}
