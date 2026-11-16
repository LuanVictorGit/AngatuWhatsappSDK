package com.angatusistemas.whatsapp.exception;

/**
 * An interaction left the queue without running: the client was closed, it expired or it was cancelled.
 *
 * @author Angatu Sistemas
 */
public final class QueueException extends WhatsAppException {

    public QueueException(String instanceId, BridgeErrorCode errorCode, String message) {
        super(instanceId, errorCode, message);
    }

    public QueueException(String instanceId, BridgeErrorCode errorCode, String message, Throwable cause) {
        super(instanceId, errorCode, message, cause);
    }
}
