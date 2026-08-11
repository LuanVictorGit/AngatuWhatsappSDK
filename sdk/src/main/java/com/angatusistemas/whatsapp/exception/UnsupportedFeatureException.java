package com.angatusistemas.whatsapp.exception;

import com.angatusistemas.whatsapp.protocol.BridgeErrorCode;

import java.util.Map;

/**
 * The requested operation is not available - either Baileys itself does not
 * support it, or this SDK has not (yet) exposed an abstraction for it. See
 * the README "Limitacoes conhecidas" section for the current list.
 */
public class UnsupportedFeatureException extends WhatsAppException {

    public UnsupportedFeatureException(String message) {
        super(message);
    }

    public UnsupportedFeatureException(String instanceId, BridgeErrorCode errorCode, String message) {
        super(instanceId, errorCode, message);
    }

    public UnsupportedFeatureException(String instanceId, BridgeErrorCode errorCode, String message, Throwable cause,
            Map<String, Object> details) {
        super(instanceId, errorCode, message, cause, details);
    }
}
