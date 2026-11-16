package com.angatusistemas.whatsapp.runtime;

import br.com.angatusistemas.lib.console.Console;
import com.angatusistemas.whatsapp.exception.BridgeErrorCode;
import com.angatusistemas.whatsapp.exception.BridgeException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds a Node.js already installed on the machine: an explicit path (system property
 * angatu.whatsapp.node.path or ANGATU_WHATSAPP_NODE_PATH), then PATH, then the usual install
 * locations. Each candidate is asked for its version and kept only when it is supported.
 *
 * @author Angatu Sistemas
 */
public final class NodeRuntimeLocator {

    private static final String PROPERTY_OVERRIDE = "angatu.whatsapp.node.path";
    private static final String ENV_OVERRIDE = "ANGATU_WHATSAPP_NODE_PATH";
    private static final long PROBE_TIMEOUT_MS = 5_000;
    private static final Pattern PATH_SEPARATOR = Pattern.compile(Pattern.quote(File.pathSeparator));

    private NodeRuntimeLocator() {
    }

    public static Optional<NodeExecutable> locate() {
        Optional<Path> override = explicitOverride();
        if (override.isPresent()) {
            Path path = override.get();
            NodeVersion version = probe(path).orElseThrow(() -> new BridgeException(null, BridgeErrorCode.INTERNAL_ERROR,
                    "the Node.js set in " + PROPERTY_OVERRIDE + "/" + ENV_OVERRIDE + " cannot run: " + path));
            if (!version.isSupported()) {
                throw new BridgeException(null, BridgeErrorCode.INTERNAL_ERROR, "the Node.js set in " + PROPERTY_OVERRIDE
                        + " is " + version + "; " + NodeVersion.MIN_SUPPORTED_MAJOR + ".x or newer is required: " + path);
            }
            return Optional.of(new NodeExecutable(path, version, NodeExecutable.Source.SYSTEM));
        }
        for (Path candidate : candidates()) {
            Optional<NodeVersion> version = probe(candidate);
            if (version.isPresent() && version.get().isSupported()) {
                return Optional.of(new NodeExecutable(candidate, version.get(), NodeExecutable.Source.SYSTEM));
            }
        }
        Console.info("[whatsapp] no supported Node.js (%s.x+) found on this machine", NodeVersion.MIN_SUPPORTED_MAJOR);
        return Optional.empty();
    }

    private static Optional<Path> explicitOverride() {
        String value = System.getProperty(PROPERTY_OVERRIDE);
        if (value == null || value.isBlank()) {
            value = System.getenv(ENV_OVERRIDE);
        }
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(Path.of(value));
    }

    private static List<Path> candidates() {
        boolean windows = isWindows();
        String executable = windows ? "node.exe" : "node";
        Set<Path> found = new LinkedHashSet<>();
        String path = System.getenv("PATH");
        if (path != null) {
            for (String directory : PATH_SEPARATOR.split(path)) {
                if (!directory.isBlank()) {
                    addIfFile(found, Path.of(directory, executable));
                }
            }
        }
        for (Path location : commonLocations(windows)) {
            addIfFile(found, location);
        }
        return new ArrayList<>(found);
    }

    private static List<Path> commonLocations(boolean windows) {
        List<Path> locations = new ArrayList<>();
        if (windows) {
            addFromEnv(locations, "ProgramFiles", "nodejs", "node.exe");
            addFromEnv(locations, "ProgramFiles(x86)", "nodejs", "node.exe");
            addFromEnv(locations, "LOCALAPPDATA", "Programs" + File.separator + "nodejs", "node.exe");
            addFromEnv(locations, "NVM_SYMLINK", "", "node.exe");
            return locations;
        }
        locations.add(Path.of("/usr/local/bin/node"));
        locations.add(Path.of("/usr/bin/node"));
        locations.add(Path.of("/opt/homebrew/bin/node"));
        String home = System.getProperty("user.home");
        if (home != null) {
            locations.add(Path.of(home, ".volta", "bin", "node"));
            Path nvm = Path.of(home, ".nvm", "versions", "node");
            if (Files.isDirectory(nvm)) {
                try (Stream<Path> versions = Files.list(nvm)) {
                    versions.map(dir -> dir.resolve("bin").resolve("node")).sorted(Comparator.reverseOrder()).forEach(locations::add);
                } catch (IOException e) {
                    // an unreadable nvm folder only removes candidates
                }
            }
        }
        return locations;
    }

    private static void addFromEnv(List<Path> locations, String variable, String subPath, String executable) {
        String base = System.getenv(variable);
        if (base != null && !base.isBlank()) {
            locations.add(Path.of(base, subPath, executable));
        }
    }

    private static void addIfFile(Set<Path> found, Path path) {
        if (Files.isRegularFile(path)) {
            found.add(path.toAbsolutePath().normalize());
        }
    }

    static Optional<NodeVersion> probe(Path executable) {
        if (!Files.isRegularFile(executable)) {
            return Optional.empty();
        }
        try {
            Process process = new ProcessBuilder(executable.toString(), "--version").redirectErrorStream(true).start();
            if (!process.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.exitValue() == 0 ? NodeVersion.parse(output) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
