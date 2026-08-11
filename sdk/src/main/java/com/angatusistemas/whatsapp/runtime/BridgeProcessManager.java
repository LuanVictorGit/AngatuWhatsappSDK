package com.angatusistemas.whatsapp.runtime;

import com.angatusistemas.whatsapp.exception.BridgeException;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Owns exactly one bridge OS process: resolving/installing what it takes to
 * run it (Node executable, bundled {@code bridge.mjs}), spawning it with a
 * freshly generated auth token, learning which port it bound to, forwarding
 * its log output, and terminating it.
 *
 * <p>This class deliberately knows nothing about the WebSocket protocol -
 * that is {@code BridgeConnection}'s job. The reason is platform-specific
 * and worth spelling out: on Windows, {@link Process#destroy()} terminates
 * the child immediately ({@code TerminateProcess}), it does not give the
 * Node process a chance to run its {@code SIGTERM} handler the way it does
 * on Linux/macOS, so OS-level termination alone cannot reliably produce a
 * graceful shutdown on every platform this SDK supports. {@link #stop} is
 * therefore a safety net, not the primary shutdown path - callers (see
 * {@code WhatsAppClient.close()}) are expected to ask the bridge to shut
 * down over the WebSocket connection first (which works identically on
 * every OS because it is not signal-based) and fall back to {@link #stop}
 * only if that does not complete in time or the connection is already gone.
 */
public final class BridgeProcessManager {

    private static final Logger log = LoggerFactory.getLogger(BridgeProcessManager.class);
    private static final Duration FORCE_KILL_TIMEOUT = Duration.ofSeconds(10);
    private static final int TOKEN_BYTES = 32;

    private final Process process;
    private final NodeExecutable node;
    private final String authToken;
    private final CompletableFuture<Integer> portReady = new CompletableFuture<>();
    private final CompletableFuture<Void> processExited = new CompletableFuture<>();

    private BridgeProcessManager(Process process, NodeExecutable node, String authToken) {
        this.process = process;
        this.node = node;
        this.authToken = authToken;

        startReaderThread(process.getInputStream(), true);
        startReaderThread(process.getErrorStream(), false);

        process.onExit().thenRun(() -> {
            int exitCode = process.exitValue();
            if (!portReady.isDone()) {
                portReady.completeExceptionally(new BridgeException(
                        "processo do bridge encerrou (exit=" + exitCode + ") antes de sinalizar que estava pronto"));
            }
            if (exitCode == 0) {
                log.info("processo do bridge encerrado normalmente (pid={})", process.pid());
            } else {
                log.warn("processo do bridge encerrou inesperadamente (pid={}, exit={})", process.pid(), exitCode);
            }
            processExited.complete(null);
        });
    }

    public static BridgeProcessManager start(StartOptions options) {
        NodeExecutable node = resolveNode(options);
        Path bridgeScript = new BridgeInstaller().install();
        String token = generateToken();

        ProcessBuilder builder = new ProcessBuilder(List.of(node.path().toString(), bridgeScript.toString()));
        builder.environment().put("ANGATU_BRIDGE_TOKEN", token);
        if (options.host() != null) {
            builder.environment().put("ANGATU_BRIDGE_HOST", options.host());
        }
        if (options.port() != null) {
            builder.environment().put("ANGATU_BRIDGE_PORT", String.valueOf(options.port()));
        }
        if (options.logLevel() != null) {
            builder.environment().put("ANGATU_BRIDGE_LOG_LEVEL", options.logLevel());
        }
        // No redirectInput() call: the bridge never reads stdin, so the default PIPE
        // (left unwritten and unclosed by this side) is harmless. Redirect.DISCARD is
        // only valid for output redirects (it wraps a write-mode null-device handle),
        // so using it here would throw IllegalArgumentException.

        log.info("iniciando processo do bridge (node={}, versao={}, origem={})", node.path(), node.version(), node.source());
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new BridgeException("falha ao iniciar o processo do bridge (executavel node=" + node.path() + ")", e);
        }

        return new BridgeProcessManager(process, node, token);
    }

    private static NodeExecutable resolveNode(StartOptions options) {
        return new NodeRuntimeLocator().locate()
                .orElseGet(() -> {
                    String version = options.nodeVersion() != null ? options.nodeVersion() : NodeRuntimeDownloader.DEFAULT_VERSION;
                    return new NodeRuntimeDownloader(version).downloadAndInstall();
                });
    }

    private static String generateToken() {
        byte[] randomBytes = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    private void startReaderThread(InputStream stream, boolean isStdout) {
        Thread thread = new Thread(() -> readLoop(stream, isStdout),
                "angatu-bridge-" + (isStdout ? "stdout" : "stderr") + "-" + System.identityHashCode(this));
        thread.setDaemon(true);
        thread.start();
    }

    private void readLoop(InputStream stream, boolean isStdout) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode parsed = BridgeLogForwarder.forward(line);
                if (isStdout && !portReady.isDone() && parsed != null && "bridge_ready".equals(parsed.path("msg").asText())) {
                    completePortFromSentinel(parsed, line);
                }
            }
        } catch (IOException e) {
            if (!portReady.isDone()) {
                portReady.completeExceptionally(new BridgeException("falha ao ler a saida do processo do bridge", e));
            }
        }
    }

    private void completePortFromSentinel(JsonNode sentinel, String rawLine) {
        int port = sentinel.path("port").asInt(-1);
        if (port > 0 && port <= 65535) {
            portReady.complete(port);
        } else {
            portReady.completeExceptionally(
                    new BridgeException("bridge sinalizou pronto mas nao informou uma porta valida: " + rawLine));
        }
    }

    /** The freshly generated shared secret this process was started with; never logged. */
    public String authToken() {
        return authToken;
    }

    public NodeExecutable node() {
        return node;
    }

    public long pid() {
        return process.pid();
    }

    /** Completes with the OS-assigned port once the bridge's readiness sentinel is observed on stdout. */
    public CompletableFuture<Integer> portReady() {
        return portReady;
    }

    /** Completes when the OS process terminates, for any reason. */
    public CompletableFuture<Void> onExit() {
        return processExited;
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    /**
     * OS-level termination: {@link Process#destroy()}, wait up to
     * {@code gracePeriod}, then {@link Process#destroyForcibly()} if it is
     * still alive. See the class Javadoc for why this is a fallback rather
     * than the primary shutdown path.
     */
    public boolean stop(Duration gracePeriod) {
        if (!process.isAlive()) {
            return true;
        }

        log.info("encerrando processo do bridge (pid={})", process.pid());
        process.destroy();
        try {
            if (process.waitFor(gracePeriod.toMillis(), TimeUnit.MILLISECONDS)) {
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }

        log.warn("processo do bridge nao encerrou em {} - forcando termino (pid={})", gracePeriod, process.pid());
        process.destroyForcibly();
        try {
            boolean exited = process.waitFor(FORCE_KILL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!exited) {
                log.error("nao foi possivel confirmar o termino forcado do processo do bridge (pid={}) - "
                        + "um processo orfao pode ter restado em execucao", process.pid());
            }
            return exited;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public record StartOptions(String host, Integer port, String logLevel, String nodeVersion) {

        public static StartOptions defaults() {
            return new StartOptions(null, null, null, null);
        }
    }
}
