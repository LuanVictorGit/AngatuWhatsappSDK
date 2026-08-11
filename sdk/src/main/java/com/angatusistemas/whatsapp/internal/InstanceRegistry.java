package com.angatusistemas.whatsapp.internal;

import com.angatusistemas.whatsapp.exception.InstanceException;
import com.angatusistemas.whatsapp.protocol.BridgeErrorCode;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JVM-wide guard against two {@code WhatsAppClient} instances using the same
 * {@code instanceId} or {@code sessionDirectory}. This is a separate check
 * from the bridge's own {@code InstanceManager}, which only prevents
 * conflicts between instances sharing one bridge OS process; since each
 * {@code WhatsAppClient} runs its own bridge process (see
 * {@code WhatsAppClient}'s class Javadoc for why), two clients pointed at
 * the same session directory would otherwise both call Baileys'
 * {@code useMultiFileAuthState} on it from two separate processes and
 * corrupt each other's auth state on disk - this has to be caught here,
 * before either bridge process is even started.
 */
public final class InstanceRegistry {

    private static final Map<String, String> SESSION_DIR_BY_INSTANCE_ID = new ConcurrentHashMap<>();
    private static final Map<String, String> INSTANCE_ID_BY_SESSION_DIR = new ConcurrentHashMap<>();

    private InstanceRegistry() {
    }

    public static synchronized void register(String instanceId, Path sessionDirectory) {
        String canonicalDir = canonicalize(sessionDirectory);

        if (SESSION_DIR_BY_INSTANCE_ID.containsKey(instanceId)) {
            throw new InstanceException(instanceId, BridgeErrorCode.INSTANCE_ALREADY_EXISTS,
                    "ja existe um WhatsAppClient ativo com instanceId '" + instanceId + "' nesta JVM");
        }
        String existingOwner = INSTANCE_ID_BY_SESSION_DIR.get(canonicalDir);
        if (existingOwner != null) {
            throw new InstanceException(instanceId, BridgeErrorCode.INSTANCE_ALREADY_EXISTS,
                    "sessionDirectory '" + canonicalDir + "' ja esta em uso pelo instanceId '" + existingOwner + "' nesta JVM");
        }

        SESSION_DIR_BY_INSTANCE_ID.put(instanceId, canonicalDir);
        INSTANCE_ID_BY_SESSION_DIR.put(canonicalDir, instanceId);
    }

    public static synchronized void unregister(String instanceId) {
        String canonicalDir = SESSION_DIR_BY_INSTANCE_ID.remove(instanceId);
        if (canonicalDir != null) {
            INSTANCE_ID_BY_SESSION_DIR.remove(canonicalDir);
        }
    }

    private static String canonicalize(Path sessionDirectory) {
        return sessionDirectory.toAbsolutePath().normalize().toString();
    }
}
