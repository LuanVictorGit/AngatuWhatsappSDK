package com.angatusistemas.whatsapp.exception;

import com.angatusistemas.whatsapp.protocol.BridgeErrorCode;

import java.util.Map;

/**
 * WhatsApp rejected the session (bad/expired credentials, banned number,
 * multi-device mismatch). The local session is no longer usable and a new
 * QR code or pairing code is required.
 */
public class AuthenticationException extends WhatsAppException {

    public AuthenticationException(String message) {
        super(message);
    }

    public AuthenticationException(String instanceId, BridgeErrorCode errorCode, String message) {
        super(instanceId, errorCode, message);
    }

    public AuthenticationException(String instanceId, BridgeErrorCode errorCode, String message, Throwable cause,
            Map<String, Object> details) {
        super(instanceId, errorCode, message, cause, details);
    }
}
