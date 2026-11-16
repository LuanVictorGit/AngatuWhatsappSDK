package com.angatusistemas.whatsapp.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.angatusistemas.whatsapp.DisconnectCause;
import com.angatusistemas.whatsapp.options.BridgeOptions;
import com.angatusistemas.whatsapp.runtime.NodeRuntimeLocator;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The link survives the death of the bridge: the process is killed mid-session, the client is told
 * the link was lost, a new bridge comes up on its own and the client is asked to recreate its
 * account there. Runs the real bundled bridge, never connecting to WhatsApp.
 *
 * @author Angatu Sistemas
 */
class BridgeRecoveryTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @TempDir
    Path sessions;

    @Test
    void restartsAKilledBridgeAndAsksTheClientToRecreateItsAccount() throws Exception {
        assumeTrue(NodeRuntimeLocator.locate().isPresent(), "Node.js is not installed on this machine");
        BridgeHost host = BridgeHost.shared();
        CountDownLatch lost = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<DisconnectCause> cause = new AtomicReference<>();
        host.attach("recovery", new BridgeHost.Attachment() {
            @Override
            public void onBridgeEvent(String event, JsonObject data) {
            }

            @Override
            public void onBridgeReady() {
                ready.countDown();
            }

            @Override
            public void onBridgeLost(DisconnectCause reason, String detail) {
                cause.set(reason);
                lost.countDown();
            }
        }, BridgeOptions.defaults());
        try {
            host.ensureRunning().get(90, TimeUnit.SECONDS);
            create(host);
            long firstPid = host.pid();

            ProcessHandle bridge = ProcessHandle.of(firstPid).orElseThrow();
            bridge.destroyForcibly();
            bridge.onExit().get(15, TimeUnit.SECONDS);

            assertTrue(lost.await(20, TimeUnit.SECONDS), "the client was not told the link was lost");
            assertEquals(DisconnectCause.BRIDGE_CRASH, cause.get());
            assertTrue(ready.await(60, TimeUnit.SECONDS), "the bridge did not come back on its own");
            assertTrue(host.isOpen());
            assertNotEquals(firstPid, host.pid());
            assertEquals("DISCONNECTED", create(host).get("state").getAsString());
        } finally {
            host.detach("recovery");
        }
    }

    private JsonObject create(BridgeHost host) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("sessionDirectory", sessions.resolve("recovery").toString());
        return host.request("create_instance", "recovery", payload, TIMEOUT).get(30, TimeUnit.SECONDS);
    }
}
