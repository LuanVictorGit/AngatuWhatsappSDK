package com.angatusistemas.whatsapp.exception;

import com.angatusistemas.whatsapp.protocol.BridgeErrorCode;
import com.angatusistemas.whatsapp.protocol.ErrorPayload;

/**
 * Single place that decides which concrete {@link WhatsAppException}
 * subtype represents a given bridge {@link ErrorPayload}. Keeping this
 * mapping in one spot means adding a new {@link BridgeErrorCode} only
 * requires a change here, not at every call site that handles bridge
 * responses.
 */
public final class BridgeExceptionTranslator {

    private BridgeExceptionTranslator() {
    }

    public static WhatsAppException translate(String instanceId, ErrorPayload error) {
        BridgeErrorCode code = BridgeErrorCode.fromWireValue(error.code());
        String message = error.message();
        return switch (code) {
            case INSTANCE_NOT_FOUND, INSTANCE_ALREADY_EXISTS ->
                    new InstanceException(instanceId, code, message, null, error.details());
            case NOT_CONNECTED, CONNECTION_FAILED ->
                    new ConnectionException(instanceId, code, message, null, error.details());
            case AUTH_FAILED ->
                    new AuthenticationException(instanceId, code, message, null, error.details());
            case SEND_FAILED, MEDIA_NOT_FOUND ->
                    new MessageSendException(instanceId, code, message, null, error.details());
            case TIMEOUT ->
                    new TimeoutException(instanceId, code, message, null, error.details());
            case UNSUPPORTED_FEATURE ->
                    new UnsupportedFeatureException(instanceId, code, message, null, error.details());
            case INVALID_TOKEN, PROTOCOL_VERSION_MISMATCH, INVALID_MESSAGE, UNKNOWN_ACTION, INVALID_PAYLOAD,
                    INTERNAL_ERROR, UNKNOWN ->
                    new BridgeException(instanceId, code, message, null, error.details());
        };
    }
}
