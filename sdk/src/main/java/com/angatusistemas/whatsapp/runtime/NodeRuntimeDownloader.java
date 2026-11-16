package com.angatusistemas.whatsapp.runtime;

import br.com.angatusistemas.lib.console.Console;
import com.angatusistemas.whatsapp.exception.BridgeErrorCode;
import com.angatusistemas.whatsapp.exception.BridgeException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Downloads an official Node.js build from nodejs.org when the machine has none, verifies it
 * against the published SHA-256 list and keeps only the executable in the cache directory.
 * HttpURLConnection on purpose: the JDK HttpClient opens a selector that fails in some locked-down
 * Windows environments.
 *
 * @author Angatu Sistemas
 */
public final class NodeRuntimeDownloader {

    public static final String DEFAULT_VERSION = "24.22.3";

    private static final String DIST = "https://nodejs.org/dist";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 300_000;

    private NodeRuntimeDownloader() {
    }

    public static NodeExecutable install(String requestedVersion) {
        String version = requestedVersion == null ? DEFAULT_VERSION : requestedVersion;
        NodeVersion parsed = NodeVersion.parse(version).orElseThrow(() -> failure("invalid Node.js version: " + version, null));
        Platform platform = Platform.current();
        Path installDirectory = RuntimeCacheDirectory.nodeDirectory().resolve(version + "-" + platform.tag);
        Path executable = installDirectory.resolve(platform.executableName());
        if (Files.isRegularFile(executable)) {
            return new NodeExecutable(executable, parsed, NodeExecutable.Source.DOWNLOADED);
        }

        Console.info("[whatsapp] downloading Node.js %s (%s)", version, platform.tag);
        String remoteFile = platform.remoteFile(version);
        String expected = expectedChecksum(URI.create(DIST + "/v" + version + "/SHASUMS256.txt"), remoteFile);
        Path temporary = createTempDirectory();
        try {
            Path archive = temporary.resolve(remoteFile.substring(remoteFile.lastIndexOf('/') + 1));
            String actual = download(URI.create(DIST + "/v" + version + "/" + remoteFile), archive);
            if (!actual.equalsIgnoreCase(expected)) {
                throw failure("Node.js download checksum mismatch (expected " + expected + ", got " + actual + ")", null);
            }
            Files.createDirectories(installDirectory);
            if (platform.standalone) {
                Files.move(archive, executable, StandardCopyOption.REPLACE_EXISTING);
            } else {
                extract(archive, "node-v" + version + "-" + platform.tag + "/bin/node", temporary, executable);
            }
        } catch (IOException e) {
            throw failure("failed to install Node.js in " + installDirectory, e);
        } finally {
            deleteRecursively(temporary);
        }
        markExecutable(executable);
        Console.info("[whatsapp] Node.js %s installed at %s", version, executable);
        return new NodeExecutable(executable, parsed, NodeExecutable.Source.DOWNLOADED);
    }

    private static String expectedChecksum(URI checksums, String fileName) {
        HttpURLConnection connection = open(checksums);
        try (InputStream in = connection.getInputStream()) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String[] parts = line.strip().split("\\s+", 2);
                if (parts.length == 2 && parts[1].strip().equals(fileName)) {
                    return parts[0];
                }
            }
        } catch (IOException e) {
            throw failure("failed to read " + checksums, e);
        } finally {
            connection.disconnect();
        }
        throw failure("no checksum published for " + fileName, null);
    }

    // Hashes while streaming to disk: the archive is never held in memory.
    private static String download(URI uri, Path target) throws IOException {
        HttpURLConnection connection = open(uri);
        try (DigestInputStream in = new DigestInputStream(connection.getInputStream(), MessageDigest.getInstance("SHA-256"))) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            return HexFormat.of().formatHex(in.getMessageDigest().digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available in this JVM", e);
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(URI uri) {
        try {
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "angatu-whatsapp-sdk");
            int status = connection.getResponseCode();
            if (status != 200) {
                connection.disconnect();
                throw failure("HTTP " + status + " from " + uri, null);
            }
            return connection;
        } catch (IOException e) {
            throw failure("network failure reaching " + uri, e);
        }
    }

    private static void extract(Path archive, String entry, Path workDirectory, Path destination) throws IOException {
        Path target = workDirectory.resolve("extract");
        Files.createDirectories(target);
        Process process = new ProcessBuilder(List.of("tar", "-xzf", archive.toString(), "-C", target.toString(), entry))
                .redirectErrorStream(true).start();
        try {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(2, TimeUnit.MINUTES) || process.exitValue() != 0) {
                process.destroyForcibly();
                throw failure("tar failed to extract Node.js: " + output, null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure("Node.js extraction interrupted", e);
        }
        Files.move(target.resolve(entry), destination, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void markExecutable(Path executable) {
        if (NodeRuntimeLocator.isWindows()) {
            return;
        }
        try {
            Set<PosixFilePermission> permissions = new HashSet<>(Files.getPosixFilePermissions(executable));
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
            permissions.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(executable, permissions);
        } catch (UnsupportedOperationException | IOException e) {
            Console.warn("[whatsapp] could not mark %s as executable: %s", executable, e.getMessage());
        }
    }

    private static Path createTempDirectory() {
        try {
            Files.createDirectories(RuntimeCacheDirectory.tempDirectory());
            return Files.createTempDirectory(RuntimeCacheDirectory.tempDirectory(), "node-");
        } catch (IOException e) {
            throw failure("failed to create a temporary directory for the Node.js download", e);
        }
    }

    private static void deleteRecursively(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException e) {
            Console.warn("[whatsapp] could not clean %s: %s", directory, e.getMessage());
        }
    }

    private static BridgeException failure(String message, Throwable cause) {
        return new BridgeException(null, BridgeErrorCode.INTERNAL_ERROR, message
                + " - or install Node.js " + NodeVersion.MIN_SUPPORTED_MAJOR + "+ and set ANGATU_WHATSAPP_NODE_PATH", cause);
    }

    private enum Platform {
        WINDOWS_X64("win-x64", true),
        WINDOWS_ARM64("win-arm64", true),
        LINUX_X64("linux-x64", false),
        LINUX_ARM64("linux-arm64", false),
        MAC_X64("darwin-x64", false),
        MAC_ARM64("darwin-arm64", false);

        private final String tag;
        private final boolean standalone;

        Platform(String tag, boolean standalone) {
            this.tag = tag;
            this.standalone = standalone;
        }

        String remoteFile(String version) {
            return standalone ? tag + "/node.exe" : "node-v" + version + "-" + tag + ".tar.gz";
        }

        String executableName() {
            return standalone ? "node.exe" : "node";
        }

        static Platform current() {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            boolean arm = arch.contains("aarch64") || arch.contains("arm64");
            if (os.contains("win")) {
                return arm ? WINDOWS_ARM64 : WINDOWS_X64;
            }
            if (os.contains("mac") || os.contains("darwin")) {
                return arm ? MAC_ARM64 : MAC_X64;
            }
            if (os.contains("linux")) {
                return arm ? LINUX_ARM64 : LINUX_X64;
            }
            throw failure("no Node.js download for os.name=" + os + " os.arch=" + arch, null);
        }
    }
}
