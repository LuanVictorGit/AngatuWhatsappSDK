package com.angatusistemas.whatsapp.exception;

/**
 * The requested operation is not supported by the bridge in use.
 *
 * @author Angatu Sistemas
 */
public final class UnsupportedFeatureException extends WhatsAppException {

    public UnsupportedFeatureException(String instanceId, BridgeErrorCode errorCode, String message) {
        super(instanceId, errorCode, message);
    }

    public UnsupportedFeatureException(String instanceId, BridgeErrorCode errorCode, String message, Throwable cause) {
        super(instanceId, errorCode, message, cause);
    }
}
