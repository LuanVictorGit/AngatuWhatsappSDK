package com.angatusistemas.whatsapp.exception;

/**
 * The bridge rejected the credentials of the local link, or WhatsApp rejected the session.
 *
 * @author Angatu Sistemas
 */
public final class AuthenticationException extends WhatsAppException {

    public AuthenticationException(String instanceId, BridgeErrorCode errorCode, String message) {
        super(instanceId, errorCode, message);
    }

    public AuthenticationException(String instanceId, BridgeErrorCode errorCode, String message, Throwable cause) {
        super(instanceId, errorCode, message, cause);
    }
}
