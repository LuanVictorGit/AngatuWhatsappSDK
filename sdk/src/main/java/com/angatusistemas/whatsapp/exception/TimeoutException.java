package com.angatusistemas.whatsapp.exception;

import com.angatusistemas.whatsapp.protocol.BridgeErrorCode;

import java.util.Map;

/**
 * A request to the bridge (or the bridge's own handshake/readiness signal)
 * did not complete within the configured timeout. Distinct from
 * {@link java.util.concurrent.TimeoutException} in the JDK - this one
 * carries SDK-specific diagnostic context (instanceId, bridge error code).
 */
public class TimeoutException extends WhatsAppException {

    public TimeoutException(String message) {
        super(message);
    }

    public TimeoutException(String instanceId, BridgeErrorCode errorCode, String message) {
        super(instanceId, errorCode, message);
    }

    public TimeoutException(String instanceId, BridgeErrorCode errorCode, String message, Throwable cause,
            Map<String, Object> details) {
        super(instanceId, errorCode, message, cause, details);
    }
}
