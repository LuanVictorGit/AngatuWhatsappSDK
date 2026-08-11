package com.angatusistemas.whatsapp.runtime;

import com.angatusistemas.whatsapp.exception.BridgeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Looks for a Node.js installation already on the host system that is new
 * enough to run the bridge, before {@link NodeRuntimeDownloader} is asked
 * to fetch one. Order of precedence:
 *
 * <ol>
 *   <li>Explicit override ({@code angatu.whatsapp.node.path} system
 *       property, then {@code ANGATU_WHATSAPP_NODE_PATH} environment
 *       variable) - if set but invalid, this is a configuration error and
 *       is reported as such rather than silently falling through.</li>
 *   <li>{@code node}/{@code node.exe} on {@code PATH}.</li>
 *   <li>A short list of common per-OS install locations, for the (common
 *       in practice) case where the JVM's process environment does not
 *       carry the interactive shell's {@code PATH} - e.g. Windows Services,
 *       some IDE run configurations, or a login shell that appends to
 *       {@code PATH} in a profile script the JVM's launcher does not
 *       source.</li>
 * </ol>
 */
public final class NodeRuntimeLocator {

    private static final Logger log = LoggerFactory.getLogger(NodeRuntimeLocator.class);

    private static final String PROPERTY_OVERRIDE = "angatu.whatsapp.node.path";
    private static final String ENV_OVERRIDE = "ANGATU_WHATSAPP_NODE_PATH";
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);

    public Optional<NodeExecutable> locate() {
        Optional<Path> override = explicitOverride();
        if (override.isPresent()) {
            Path path = override.get();
            NodeVersion version = probe(path).orElseThrow(() -> new BridgeException(
                    "Node.js indicado via " + PROPERTY_OVERRIDE + "/" + ENV_OVERRIDE + " nao pode ser executado: " + path));
            if (!version.satisfiesMinimum()) {
                throw new BridgeException("Node.js indicado via " + PROPERTY_OVERRIDE + "/" + ENV_OVERRIDE
                        + " e " + version + ", mas a versao minima exigida e " + NodeVersion.MIN_SUPPORTED_MAJOR + ".x: " + path);
            }
            log.info("usando Node.js {} configurado explicitamente em {}", version, path);
            return Optional.of(new NodeExecutable(path, version, NodeExecutable.NodeSource.SYSTEM));
        }

        for (Path candidate : candidatePaths()) {
            Optional<NodeVersion> version = probe(candidate);
            if (version.isPresent() && version.get().satisfiesMinimum()) {
                log.info("Node.js do sistema encontrado: {} ({})", candidate, version.get());
                return Optional.of(new NodeExecutable(candidate, version.get(), NodeExecutable.NodeSource.SYSTEM));
            }
            if (version.isPresent()) {
                log.debug("ignorando {} - versao {} abaixo do minimo exigido ({}.x)", candidate, version.get(),
                        NodeVersion.MIN_SUPPORTED_MAJOR);
            }
        }

        log.info("nenhuma instalacao compativel de Node.js encontrada no sistema");
        return Optional.empty();
    }

    private Optional<Path> explicitOverride() {
        String fromProperty = System.getProperty(PROPERTY_OVERRIDE);
        if (fromProperty != null && !fromProperty.isBlank()) {
            return Optional.of(Paths.get(fromProperty));
        }
        String fromEnv = System.getenv(ENV_OVERRIDE);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Optional.of(Paths.get(fromEnv));
        }
        return Optional.empty();
    }

    private List<Path> candidatePaths() {
        Set<Path> candidates = new LinkedHashSet<>();
        boolean windows = isWindows();
        String executableName = windows ? "node.exe" : "node";

        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                if (dir.isBlank()) {
                    continue;
                }
                addIfRegularFile(candidates, Paths.get(dir, executableName));
            }
        }

        for (Path commonLocation : commonInstallLocations(windows)) {
            addIfRegularFile(candidates, commonLocation);
        }

        return new ArrayList<>(candidates);
    }

    private List<Path> commonInstallLocations(boolean windows) {
        List<Path> locations = new ArrayList<>();
        if (windows) {
            addEnvBasedPath(locations, "ProgramFiles", "nodejs", "node.exe");
            addEnvBasedPath(locations, "ProgramFiles(x86)", "nodejs", "node.exe");
            addEnvBasedPath(locations, "LOCALAPPDATA", "Programs" + java.io.File.separator + "nodejs", "node.exe");
            String nvmSymlink = System.getenv("NVM_SYMLINK");
            if (nvmSymlink != null && !nvmSymlink.isBlank()) {
                locations.add(Paths.get(nvmSymlink, "node.exe"));
            }
        } else {
            locations.add(Paths.get("/usr/local/bin/node"));
            locations.add(Paths.get("/usr/bin/node"));
            locations.add(Paths.get("/opt/homebrew/bin/node"));
            String home = System.getProperty("user.home");
            if (home != null) {
                locations.add(Paths.get(home, ".volta", "bin", "node"));
                locations.addAll(latestNvmVersion(Paths.get(home, ".nvm", "versions", "node")));
            }
        }
        return locations;
    }

    /** nvm installs each version under {@code versions/node/vX.Y.Z/bin/node}; pick the highest if present. */
    private List<Path> latestNvmVersion(Path nvmVersionsDir) {
        if (!Files.isDirectory(nvmVersionsDir)) {
            return List.of();
        }
        try (var stream = Files.list(nvmVersionsDir)) {
            return stream
                    .map(dir -> dir.resolve("bin").resolve("node"))
                    .filter(Files::isRegularFile)
                    .sorted((a, b) -> b.compareTo(a))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private void addEnvBasedPath(List<Path> locations, String envVar, String subPath, String executable) {
        String base = System.getenv(envVar);
        if (base != null && !base.isBlank()) {
            locations.add(Paths.get(base, subPath, executable));
        }
    }

    private void addIfRegularFile(Set<Path> candidates, Path path) {
        if (Files.isRegularFile(path)) {
            candidates.add(path.toAbsolutePath().normalize());
        }
    }

    private Optional<NodeVersion> probe(Path executable) {
        if (!Files.isRegularFile(executable)) {
            return Optional.empty();
        }
        try {
            Process process = new ProcessBuilder(executable.toString(), "--version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return Optional.empty();
            }
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                return Optional.empty();
            }
            return NodeVersion.parse(output);
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
