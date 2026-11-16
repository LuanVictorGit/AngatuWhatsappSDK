package com.angatusistemas.whatsapp.exception;

/**
 * WhatsApp refused or failed a message, presence or receipt the SDK tried to send.
 *
 * @author Angatu Sistemas
 */
public final class MessageSendException extends WhatsAppException {

    public MessageSendException(String instanceId, BridgeErrorCode errorCode, String message) {
        super(instanceId, errorCode, message);
    }

    public MessageSendException(String instanceId, BridgeErrorCode errorCode, String message, Throwable cause) {
        super(instanceId, errorCode, message, cause);
    }
}
