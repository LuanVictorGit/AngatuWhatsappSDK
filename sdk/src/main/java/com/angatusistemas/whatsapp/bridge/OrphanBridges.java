package com.angatusistemas.whatsapp.bridge;

import br.com.angatusistemas.lib.console.Console;
import com.angatusistemas.whatsapp.internal.Json;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Stops a bridge left running by a previous run of the application that still holds a session.
 * After a crash or a fast restart, the old bridge keeps the WhatsApp socket and the session lock
 * for up to two minutes; the new one could not take the account, and if it did, WhatsApp would
 * drop one of them with connectionReplaced. Only a live Node process named in the session lock is
 * stopped, never the current bridge.
 *
 * @author Angatu Sistemas
 */
public final class OrphanBridges {

    private static final long STOP_WAIT_SECONDS = 10;

    private OrphanBridges() {
    }

    public static void reap(Path sessionDirectory, long currentBridgePid) {
        Path lock = sessionDirectory.resolve(".lock");
        if (!Files.isRegularFile(lock)) {
            return;
        }
        long pid;
        try {
            pid = Json.number(JsonParser.parseString(Files.readString(lock, StandardCharsets.UTF_8)).getAsJsonObject(), "pid", -1);
        } catch (IOException | JsonParseException | IllegalStateException e) {
            return;
        }
        if (pid <= 0 || pid == currentBridgePid || pid == ProcessHandle.current().pid()) {
            return;
        }
        Optional<ProcessHandle> owner = ProcessHandle.of(pid).filter(ProcessHandle::isAlive).filter(OrphanBridges::isBridge);
        if (owner.isEmpty()) {
            return;
        }
        Console.warn("[whatsapp] stopping an orphaned bridge (pid %s) that still holds %s", pid, sessionDirectory);
        ProcessHandle process = owner.get();
        process.destroy();
        try {
            process.onExit().get(STOP_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            process.destroyForcibly();
        }
    }

    private static boolean isBridge(ProcessHandle process) {
        ProcessHandle.Info info = process.info();
        String command = info.command().orElse("").toLowerCase(Locale.ROOT);
        boolean node = command.endsWith("node") || command.endsWith("node.exe");
        // The command line is not visible on every platform; when it is, it must be our script.
        return node && info.commandLine().map(line -> line.contains("bridge.mjs")).orElse(true);
    }
}
