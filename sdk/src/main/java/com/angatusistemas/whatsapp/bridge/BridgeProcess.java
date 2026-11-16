package com.angatusistemas.whatsapp.bridge;

import br.com.angatusistemas.lib.console.Console;
import com.angatusistemas.whatsapp.exception.BridgeErrorCode;
import com.angatusistemas.whatsapp.exception.BridgeException;
import com.angatusistemas.whatsapp.internal.Json;
import com.angatusistemas.whatsapp.options.BridgeOptions;
import com.angatusistemas.whatsapp.runtime.BridgeInstaller;
import com.angatusistemas.whatsapp.runtime.NodeExecutable;
import com.angatusistemas.whatsapp.runtime.NodeRuntimeDownloader;
import com.angatusistemas.whatsapp.runtime.NodeRuntimeLocator;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The Node.js bridge process: started with the V8 flags that trade CPU for memory (small young
 * generation, optimize-for-size, a heap cap), authenticated by a random token passed through the
 * environment, and with its JSON log lines re-emitted on the Angatu Console. Its stdout tells the
 * port the OS assigned.
 *
 * @author Angatu Sistemas
 */
public final class BridgeProcess {

    private static final int TOKEN_BYTES = 32;
    private static final Set<String> LOG_ENVELOPE = Set.of("level", "time", "pid", "hostname", "msg", "v");

    private final Process process;
    private final String token;
    private final CompletableFuture<Integer> port = new CompletableFuture<>();

    private BridgeProcess(Process process, String token) {
        this.process = process;
        this.token = token;
        Thread.ofVirtual().name("angatu-whatsapp-bridge-out").start(() -> read(process.getInputStream()));
        Thread.ofVirtual().name("angatu-whatsapp-bridge-err").start(() -> read(process.getErrorStream()));
        process.onExit().thenRun(() -> port.completeExceptionally(new BridgeException(null, BridgeErrorCode.INTERNAL_ERROR,
                "bridge process exited (code " + process.exitValue() + ") before it was ready")));
    }

    public static BridgeProcess start(BridgeOptions options) {
        NodeExecutable node = NodeRuntimeLocator.locate().orElseGet(() -> NodeRuntimeDownloader.install(options.nodeVersion()));
        Path script = BridgeInstaller.install();
        byte[] secret = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);

        ProcessBuilder builder = new ProcessBuilder(List.of(node.path().toString(),
                "--optimize-for-size", "--max-semi-space-size=2", "--max-old-space-size=" + options.maxHeapMb(),
                script.toString()));
        Map<String, String> environment = builder.environment();
        environment.put("ANGATU_BRIDGE_TOKEN", token);
        if (options.host() != null) {
            environment.put("ANGATU_BRIDGE_HOST", options.host());
        }
        if (options.port() != null) {
            environment.put("ANGATU_BRIDGE_PORT", String.valueOf(options.port()));
        }
        if (options.logLevel() != null) {
            environment.put("ANGATU_BRIDGE_LOG_LEVEL", options.logLevel());
        }
        try {
            Process process = builder.start();
            Console.info("[whatsapp] bridge started (pid %s, node %s %s)", process.pid(), node.version(), node.source());
            return new BridgeProcess(process, token);
        } catch (IOException e) {
            throw new BridgeException(null, BridgeErrorCode.INTERNAL_ERROR, "failed to start the bridge with " + node.path(), e);
        }
    }

    public CompletableFuture<Integer> port() {
        return port;
    }

    public String token() {
        return token;
    }

    public long pid() {
        return process.pid();
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public boolean exitsWithin(Duration wait) {
        try {
            return process.waitFor(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return !process.isAlive();
        }
    }

    public void stop(Duration grace) {
        if (!process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(grace.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private void read(InputStream stream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                forward(line);
            }
        } catch (IOException e) {
            // the stream closes with the process
        }
    }

    private void forward(String line) {
        if (line.isBlank()) {
            return;
        }
        JsonObject record;
        try {
            JsonElement parsed = JsonParser.parseString(line);
            if (!parsed.isJsonObject()) {
                Console.info("[whatsapp-bridge] %s", line);
                return;
            }
            record = parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            Console.info("[whatsapp-bridge] %s", line);
            return;
        }
        String message = Json.str(record, "msg");
        if (!port.isDone() && "bridge_ready".equals(message)) {
            port.complete((int) Json.number(record, "port", -1));
        }
        StringBuilder text = new StringBuilder("[whatsapp-bridge] ").append(message == null ? "" : message);
        record.entrySet().stream().filter(entry -> !LOG_ENVELOPE.contains(entry.getKey()))
                .forEach(entry -> text.append(' ').append(entry.getKey()).append('=').append(entry.getValue()));
        long level = Json.number(record, "level", 30);
        if (level >= 50) {
            Console.error(text.toString());
        } else if (level >= 40) {
            Console.warn(text.toString());
        } else if (level >= 30) {
            Console.info(text.toString());
        } else {
            Console.debug(text.toString());
        }
    }
}
