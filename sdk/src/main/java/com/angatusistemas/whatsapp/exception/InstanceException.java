package com.angatusistemas.whatsapp.exception;

import com.angatusistemas.whatsapp.protocol.BridgeErrorCode;

import java.util.Map;

/**
 * An instance-lifecycle invariant was violated: unknown instanceId, a
 * duplicate instanceId, or a sessionDirectory already claimed by another
 * instance.
 */
public class InstanceException extends WhatsAppException {

    public InstanceException(String message) {
        super(message);
    }

    public InstanceException(String instanceId, BridgeErrorCode errorCode, String message) {
        super(instanceId, errorCode, message);
    }

    public InstanceException(String instanceId, BridgeErrorCode errorCode, String message, Throwable cause,
            Map<String, Object> details) {
        super(instanceId, errorCode, message, cause, details);
    }
}
