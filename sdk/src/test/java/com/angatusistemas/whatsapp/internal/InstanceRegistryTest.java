package com.angatusistemas.whatsapp.internal;

import com.angatusistemas.whatsapp.exception.InstanceException;
import com.angatusistemas.whatsapp.protocol.BridgeErrorCode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InstanceRegistry} is a JVM-wide static registry with no reset hook
 * (by design - see its class Javadoc), so every test here uses an
 * instanceId/sessionDirectory pair unique to that test method and always
 * unregisters what it registered, whether the test body succeeds or throws.
 * This keeps test methods independent regardless of execution order without
 * needing package-private test-only reset support in production code.
 */
class InstanceRegistryTest {

    private static Path testDir(String... segments) {
        return Path.of("angatu-instance-registry-test", segments);
    }

    @Test
    void registerSucceedsForNewInstanceIdAndSessionDirectory() {
        String instanceId = "case-1-instance";
        try {
            assertDoesNotThrow(() -> InstanceRegistry.register(instanceId, testDir("case-1")));
        } finally {
            InstanceRegistry.unregister(instanceId);
        }
    }

    @Test
    void registerRejectsDuplicateInstanceId() {
        String instanceId = "case-2-instance";
        try {
            InstanceRegistry.register(instanceId, testDir("case-2", "first"));

            InstanceException ex = assertThrows(InstanceException.class,
                    () -> InstanceRegistry.register(instanceId, testDir("case-2", "second")));

            assertEquals(instanceId, ex.instanceId().orElseThrow());
            assertEquals(BridgeErrorCode.INSTANCE_ALREADY_EXISTS, ex.errorCode().orElseThrow());
        } finally {
            InstanceRegistry.unregister(instanceId);
        }
    }

    @Test
    void registerRejectsSessionDirectoryAlreadyClaimedByDifferentInstanceId() {
        String owner = "case-3-owner";
        String challenger = "case-3-challenger";
        Path sharedDir = testDir("case-3", "shared");
        try {
            InstanceRegistry.register(owner, sharedDir);

            InstanceException ex = assertThrows(InstanceException.class,
                    () -> InstanceRegistry.register(challenger, sharedDir));

            assertEquals(challenger, ex.instanceId().orElseThrow());
            assertEquals(BridgeErrorCode.INSTANCE_ALREADY_EXISTS, ex.errorCode().orElseThrow());
            assertTrue(ex.getMessage().contains(owner), "mensagem deveria citar o dono atual: " + ex.getMessage());
        } finally {
            InstanceRegistry.unregister(owner);
            InstanceRegistry.unregister(challenger);
        }
    }

    @Test
    void registerAllowsReRegistrationAfterUnregister() {
        String instanceId = "case-4-instance";
        Path dir = testDir("case-4");
        InstanceRegistry.register(instanceId, dir);
        InstanceRegistry.unregister(instanceId);

        try {
            assertDoesNotThrow(() -> InstanceRegistry.register(instanceId, dir));
        } finally {
            InstanceRegistry.unregister(instanceId);
        }
    }

    @Test
    void unregisterOfUnknownInstanceIdIsANoOp() {
        assertDoesNotThrow(() -> InstanceRegistry.unregister("case-5-never-registered"));
    }

    @Test
    void sessionDirectoryConflictDetectionIsBasedOnNormalizedPath() {
        String owner = "case-6-owner";
        String challenger = "case-6-challenger";
        Path direct = testDir("case-6", "dir-a");
        Path viaDotDot = testDir("case-6", "dir-b", "..", "dir-a");
        try {
            InstanceRegistry.register(owner, direct);

            assertThrows(InstanceException.class, () -> InstanceRegistry.register(challenger, viaDotDot));
        } finally {
            InstanceRegistry.unregister(owner);
            InstanceRegistry.unregister(challenger);
        }
    }
}
