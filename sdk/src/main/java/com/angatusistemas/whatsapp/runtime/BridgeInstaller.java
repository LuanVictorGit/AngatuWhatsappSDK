package com.angatusistemas.whatsapp.runtime;

import br.com.angatusistemas.lib.console.Console;
import com.angatusistemas.whatsapp.exception.BridgeErrorCode;
import com.angatusistemas.whatsapp.exception.BridgeException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.stream.Stream;

/**
 * Extracts the bridge script shipped inside the jar into the cache directory, under a folder named
 * after its content hash, so a new SDK version never runs an old script. The script is streamed,
 * never loaded whole into the heap.
 *
 * @author Angatu Sistemas
 */
public final class BridgeInstaller {

    private static final String SCRIPT_RESOURCE = "/bridge/bridge.mjs";
    private static final long STALE_AFTER_MS = 24 * 60 * 60 * 1000L;

    private BridgeInstaller() {
    }

    public static Path install() {
        String hash = hashOfResource();
        Path directory = RuntimeCacheDirectory.bridgeDirectory().resolve(hash.substring(0, 32));
        Path script = directory.resolve("bridge.mjs");
        if (Files.isRegularFile(script)) {
            return script;
        }
        try (InputStream in = open()) {
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, "bridge", ".tmp");
            try (OutputStream out = Files.newOutputStream(temporary)) {
                in.transferTo(out);
            }
            Files.move(temporary, script, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new BridgeException(null, BridgeErrorCode.INTERNAL_ERROR, "failed to install the bridge script at " + script, e);
        }
        Console.info("[whatsapp] bridge script installed at %s", script);
        removeOldVersions(directory);
        return script;
    }

    // Every SDK upgrade extracts a new folder; older ones untouched for a day belong to no running bridge.
    private static void removeOldVersions(Path current) {
        long cutoff = System.currentTimeMillis() - STALE_AFTER_MS;
        try (Stream<Path> folders = Files.list(current.getParent())) {
            folders.filter(folder -> !folder.equals(current)).filter(folder -> modifiedBefore(folder, cutoff)).forEach(folder -> {
                try (Stream<Path> files = Files.walk(folder)) {
                    files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
                } catch (IOException e) {
                    Console.debug("[whatsapp] could not remove the old bridge folder %s: %s", folder, e.getMessage());
                }
            });
        } catch (IOException e) {
            Console.debug("[whatsapp] could not list old bridge folders: %s", e.getMessage());
        }
    }

    private static boolean modifiedBefore(Path folder, long cutoff) {
        try {
            return Files.getLastModifiedTime(folder).toMillis() < cutoff;
        } catch (IOException e) {
            return false;
        }
    }

    private static String hashOfResource() {
        try (DigestInputStream in = new DigestInputStream(open(), MessageDigest.getInstance("SHA-256"))) {
            in.transferTo(OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(in.getMessageDigest().digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new BridgeException(null, BridgeErrorCode.INTERNAL_ERROR, "failed to read the bundled bridge script", e);
        }
    }

    private static InputStream open() {
        InputStream in = BridgeInstaller.class.getResourceAsStream(SCRIPT_RESOURCE);
        if (in == null) {
            throw new BridgeException(null, BridgeErrorCode.INTERNAL_ERROR,
                    "bridge script missing from the classpath: the sdk jar was built without bridge/bridge.mjs");
        }
        return in;
    }
}
