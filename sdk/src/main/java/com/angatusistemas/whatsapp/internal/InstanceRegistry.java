package com.angatusistemas.whatsapp.internal;

import com.angatusistemas.whatsapp.exception.BridgeErrorCode;
import com.angatusistemas.whatsapp.exception.InstanceException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The clients alive in this JVM: one per instance id and one per session directory, because two
 * clients on one directory would open two sockets on the same account.
 *
 * @author Angatu Sistemas
 */
public final class InstanceRegistry {

    private static final Map<String, String> DIRECTORY_BY_INSTANCE = new HashMap<>();
    private static final Map<String, String> INSTANCE_BY_DIRECTORY = new HashMap<>();

    private InstanceRegistry() {
    }

    public static synchronized void register(String instanceId, Path sessionDirectory) {
        String directory = canonical(sessionDirectory);
        if (DIRECTORY_BY_INSTANCE.containsKey(instanceId)) {
            throw new InstanceException(instanceId, BridgeErrorCode.INSTANCE_ALREADY_EXISTS,
                    "a client with instance id '" + instanceId + "' already exists in this JVM");
        }
        String owner = INSTANCE_BY_DIRECTORY.get(directory);
        if (owner != null) {
            throw new InstanceException(instanceId, BridgeErrorCode.INSTANCE_ALREADY_EXISTS,
                    "session directory '" + directory + "' is already used by instance '" + owner + "'");
        }
        DIRECTORY_BY_INSTANCE.put(instanceId, directory);
        INSTANCE_BY_DIRECTORY.put(directory, instanceId);
    }

    public static synchronized void unregister(String instanceId) {
        String directory = DIRECTORY_BY_INSTANCE.remove(instanceId);
        if (directory != null) {
            INSTANCE_BY_DIRECTORY.remove(directory);
        }
    }

    private static String canonical(Path sessionDirectory) {
        return sessionDirectory.toAbsolutePath().normalize().toString();
    }
}
