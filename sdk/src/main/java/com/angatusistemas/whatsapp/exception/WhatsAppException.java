package com.angatusistemas.whatsapp.exception;

/**
 * Base of every failure the SDK reports, carrying the instance it happened on and the bridge's
 * error code so callers can branch on the code instead of parsing messages.
 *
 * @author Angatu Sistemas
 */
public class WhatsAppException extends RuntimeException {

    private final String instanceId;
    private final BridgeErrorCode errorCode;

    public WhatsAppException(String instanceId, BridgeErrorCode errorCode, String message) {
        this(instanceId, errorCode, message, null);
    }

    public WhatsAppException(String instanceId, BridgeErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.instanceId = instanceId;
        this.errorCode = errorCode == null ? BridgeErrorCode.INTERNAL_ERROR : errorCode;
    }

    public String instanceId() {
        return instanceId;
    }

    public BridgeErrorCode errorCode() {
        return errorCode;
    }
}
