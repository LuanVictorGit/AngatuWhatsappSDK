package com.angatusistemas.whatsapp.exception;

import com.angatusistemas.whatsapp.protocol.BridgeErrorCode;

import java.util.Map;
import java.util.Optional;

/**
 * Base of every checked failure the SDK can raise. Unchecked by design: the
 * public API is predominantly asynchronous ({@link java.util.concurrent.CompletableFuture}),
 * where checked exceptions do not compose (they end up wrapped in
 * {@link java.util.concurrent.CompletionException} regardless), and the
 * synchronous convenience methods would otherwise force try/catch onto
 * every call site for failures most callers handle via a listener anyway.
 *
 * <p>Every instance carries as much diagnostic context as was available at
 * the point of failure: which instance was involved, the bridge's
 * machine-readable error code (if the failure originated from the bridge),
 * and any structured details the bridge attached.
 */
public class WhatsAppException extends RuntimeException {

    private final String instanceId;
    private final BridgeErrorCode errorCode;
    private final Map<String, Object> details;

    public WhatsAppException(String message) {
        this(null, null, message, null, Map.of());
    }

    public WhatsAppException(String message, Throwable cause) {
        this(null, null, message, cause, Map.of());
    }

    public WhatsAppException(String instanceId, BridgeErrorCode errorCode, String message) {
        this(instanceId, errorCode, message, null, Map.of());
    }

    public WhatsAppException(String instanceId, BridgeErrorCode errorCode, String message, Throwable cause,
            Map<String, Object> details) {
        super(message, cause);
        this.instanceId = instanceId;
        this.errorCode = errorCode;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public Optional<String> instanceId() {
        return Optional.ofNullable(instanceId);
    }

    public Optional<BridgeErrorCode> errorCode() {
        return Optional.ofNullable(errorCode);
    }

    public Map<String, Object> details() {
        return details;
    }

    @Override
    public String getMessage() {
        String base = super.getMessage();
        if (instanceId == null && errorCode == null) {
            return base;
        }
        StringBuilder sb = new StringBuilder(base == null ? "" : base);
        sb.append(" [");
        if (instanceId != null) {
            sb.append("instanceId=").append(instanceId);
        }
        if (errorCode != null) {
            if (instanceId != null) {
                sb.append(", ");
            }
            sb.append("errorCode=").append(errorCode);
        }
        sb.append(']');
        return sb.toString();
    }
}
