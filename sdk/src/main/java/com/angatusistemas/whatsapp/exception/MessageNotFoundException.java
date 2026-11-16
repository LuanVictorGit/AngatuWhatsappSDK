package com.angatusistemas.whatsapp.exception;

/**
 * The message to quote, read or download is no longer in the short window the bridge keeps.
 *
 * @author Angatu Sistemas
 */
public final class MessageNotFoundException extends WhatsAppException {

    public MessageNotFoundException(String instanceId, BridgeErrorCode errorCode, String message) {
        super(instanceId, errorCode, message);
    }

    public MessageNotFoundException(String instanceId, BridgeErrorCode errorCode, String message, Throwable cause) {
        super(instanceId, errorCode, message, cause);
    }
}
