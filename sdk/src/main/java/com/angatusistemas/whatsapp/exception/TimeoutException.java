package com.angatusistemas.whatsapp.exception;

/**
 * An operation did not finish within its deadline; for a send, delivery is unknown.
 *
 * @author Angatu Sistemas
 */
public final class TimeoutException extends WhatsAppException {

    public TimeoutException(String instanceId, BridgeErrorCode errorCode, String message) {
        super(instanceId, errorCode, message);
    }

    public TimeoutException(String instanceId, BridgeErrorCode errorCode, String message, Throwable cause) {
        super(instanceId, errorCode, message, cause);
    }
}
