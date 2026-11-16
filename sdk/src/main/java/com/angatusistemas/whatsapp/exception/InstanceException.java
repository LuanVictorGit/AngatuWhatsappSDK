package com.angatusistemas.whatsapp.exception;

/**
 * An instance id or session directory conflicts with one already in use.
 *
 * @author Angatu Sistemas
 */
public final class InstanceException extends WhatsAppException {

    public InstanceException(String instanceId, BridgeErrorCode errorCode, String message) {
        super(instanceId, errorCode, message);
    }

    public InstanceException(String instanceId, BridgeErrorCode errorCode, String message, Throwable cause) {
        super(instanceId, errorCode, message, cause);
    }
}
