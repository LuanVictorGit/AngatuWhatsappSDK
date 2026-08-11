package com.angatusistemas.whatsapp.exception;

import com.angatusistemas.whatsapp.protocol.BridgeErrorCode;

import java.util.Map;

/** Sending a message (or downloading media attached to one) failed. */
public class MessageSendException extends WhatsAppException {

    public MessageSendException(String message) {
        super(message);
    }

    public MessageSendException(String instanceId, BridgeErrorCode errorCode, String message) {
        super(instanceId, errorCode, message);
    }

    public MessageSendException(String instanceId, BridgeErrorCode errorCode, String message, Throwable cause,
            Map<String, Object> details) {
        super(instanceId, errorCode, message, cause, details);
    }
}
