package com.angatusistemas.whatsapp.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.angatusistemas.whatsapp.DisconnectCause;
import com.angatusistemas.whatsapp.options.BridgeOptions;
import com.angatusistemas.whatsapp.runtime.NodeRuntimeLocator;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The real bundled bridge on a local Node.js, without ever connecting to WhatsApp: two accounts
 * share one process, the protocol answers, and the process ends with the last account.
 *
 * @author Angatu Sistemas
 */
class BridgeHostTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @TempDir
    Path sessions;

    @Test
    void sharesOneProcessAndStopsWithTheLastClient() throws Exception {
        assumeTrue(NodeRuntimeLocator.locate().isPresent(), "Node.js is not installed on this machine");
        BridgeHost host = BridgeHost.shared();
        host.attach("host-a", new Silent(), BridgeOptions.defaults());
        host.attach("host-b", new Silent(), BridgeOptions.defaults());
        try {
            host.ensureRunning().get(90, TimeUnit.SECONDS);
            long pid = host.pid();
            assertTrue(pid > 0);
            assertEquals("DISCONNECTED", create(host, "host-a").get("state").getAsString());
            assertEquals("DISCONNECTED", create(host, "host-b").get("state").getAsString());
            JsonObject stats = host.request("bridge_stats", null, new JsonObject(), TIMEOUT).get(30, TimeUnit.SECONDS);
            assertEquals(2, stats.getAsJsonArray("instances").size());
            assertEquals(pid, stats.get("pid").getAsLong());
            JsonObject refused = new JsonObject();
            refused.addProperty("to", "5511999990000");
            refused.addProperty("text", "oi");
            assertTrue(host.request("send_text", "host-a", refused, TIMEOUT).handle((ok, error) -> error != null).get(30, TimeUnit.SECONDS),
                    "a send on an account that is not connected must be refused");

            host.detach("host-a");
            assertTrue(host.isOpen());
            host.detach("host-b");
            assertFalse(host.isOpen());
            Optional<ProcessHandle> process = ProcessHandle.of(pid);
            assertTrue(process.isEmpty() || process.get().onExit().get(15, TimeUnit.SECONDS) != null);
        } finally {
            host.detach("host-a");
            host.detach("host-b");
        }
    }

    private JsonObject create(BridgeHost host, String id) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("sessionDirectory", sessions.resolve(id).toString());
        return host.request("create_instance", id, payload, TIMEOUT).get(30, TimeUnit.SECONDS);
    }

    private static final class Silent implements BridgeHost.Attachment {

        @Override
        public void onBridgeEvent(String event, JsonObject data) {
        }

        @Override
        public void onBridgeReady() {
        }

        @Override
        public void onBridgeLost(DisconnectCause cause, String detail) {
        }
    }
}
