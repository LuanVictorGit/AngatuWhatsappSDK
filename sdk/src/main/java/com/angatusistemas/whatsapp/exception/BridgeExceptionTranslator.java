package com.angatusistemas.whatsapp.exception;

/**
 * Turns an error code reported by the bridge into the matching exception type, so every failure
 * that crosses the process boundary arrives typed.
 *
 * @author Angatu Sistemas
 */
public final class BridgeExceptionTranslator {

    private BridgeExceptionTranslator() {
    }

    public static WhatsAppException translate(String instanceId, BridgeErrorCode code, String message) {
        return switch (code) {
            case NOT_CONNECTED, CONNECTION_FAILED -> new ConnectionException(instanceId, code, message);
            case AUTH_FAILED, INVALID_TOKEN -> new AuthenticationException(instanceId, code, message);
            case SEND_FAILED -> new MessageSendException(instanceId, code, message);
            case TIMEOUT -> new TimeoutException(instanceId, code, message);
            case RATE_LIMITED -> new RateLimitException(instanceId, code, message);
            case MEDIA_NOT_FOUND -> new MessageNotFoundException(instanceId, code, message);
            case INSTANCE_NOT_FOUND, INSTANCE_ALREADY_EXISTS -> new InstanceException(instanceId, code, message);
            case UNSUPPORTED_FEATURE, UNKNOWN_ACTION -> new UnsupportedFeatureException(instanceId, code, message);
            case QUEUE_CLOSED, EXPIRED, CANCELLED -> new QueueException(instanceId, code, message);
            case PROTOCOL_VERSION_MISMATCH, INVALID_MESSAGE, INVALID_PAYLOAD, INTERNAL_ERROR -> new BridgeException(instanceId, code, message);
        };
    }
}
