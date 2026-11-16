package com.angatusistemas.whatsapp.exception;

/**
 * A read was refused because the per-minute read cap that protects the account was reached.
 *
 * @author Angatu Sistemas
 */
public final class RateLimitException extends WhatsAppException {

    public RateLimitException(String instanceId, BridgeErrorCode errorCode, String message) {
        super(instanceId, errorCode, message);
    }

    public RateLimitException(String instanceId, BridgeErrorCode errorCode, String message, Throwable cause) {
        super(instanceId, errorCode, message, cause);
    }
}
