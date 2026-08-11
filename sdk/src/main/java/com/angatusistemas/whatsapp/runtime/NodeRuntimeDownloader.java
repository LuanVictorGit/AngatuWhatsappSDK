package com.angatusistemas.whatsapp.runtime;

import com.angatusistemas.whatsapp.exception.BridgeException;
import com.angatusistemas.whatsapp.protocol.BridgeErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Downloads and installs a Node.js runtime into the local cache
 * ({@link RuntimeCacheDirectory#nodeDirectory()}) when {@link NodeRuntimeLocator}
 * could not find a compatible one already on the host.
 *
 * <p>Windows fetches the standalone {@code node.exe} nodejs.org publishes
 * per architecture directly - no archive, no extraction. Linux/macOS only
 * publish a {@code .tar.gz}, so those extract just the {@code bin/node}
 * entry by shelling out to the system {@code tar} binary (present by
 * default on effectively every Linux distribution and macOS release; this
 * avoids pulling in a general-purpose archive library for a single entry
 * out of one file).
 *
 * <p>Integrity: the expected SHA-256 is fetched live from nodejs.org's own
 * {@code SHASUMS256.txt} for the exact version being installed (over TLS,
 * from the same trusted origin as the binary itself) rather than hardcoded
 * in source, so this keeps working as new Node.js versions are released and
 * never trusts a value baked in at a single point in time. This is the same
 * trust model widely used by version managers such as nvm/volta/fnm; full
 * GPG signature verification of the release itself is a possible future
 * hardening step (see README "Limitacoes conhecidas").
 */
public final class NodeRuntimeDownloader {

    private static final Logger log = LoggerFactory.getLogger(NodeRuntimeDownloader.class);

    /** Current Node.js Active LTS ("Krypton") at the time this SDK was built. */
    public static final String DEFAULT_VERSION = "24.19.0";

    private static final String DIST_BASE_URL = "https://nodejs.org/dist";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

    private final String nodeVersion;

    public NodeRuntimeDownloader() {
        this(DEFAULT_VERSION);
    }

    public NodeRuntimeDownloader(String nodeVersion) {
        this.nodeVersion = Objects.requireNonNull(nodeVersion, "nodeVersion");
    }

    public NodeExecutable downloadAndInstall() {
        Platform platform = detectCurrent();
        NodeVersion version = NodeVersion.parse(nodeVersion)
                .orElseThrow(() -> new BridgeException("Versao de Node.js configurada e invalida: " + nodeVersion));

        Path installDir = RuntimeCacheDirectory.nodeDirectory().resolve(nodeVersion + "-" + platform.tag);
        Path executable = installDir.resolve(platform.localExecutableName());

        if (Files.isRegularFile(executable)) {
            log.info("runtime Node.js {} ja esta em cache em {}", version, executable);
            return new NodeExecutable(executable, version, NodeExecutable.NodeSource.DOWNLOADED);
        }

        log.info("nenhuma instalacao Node.js compativel encontrada no sistema - baixando Node.js {} ({}) automaticamente",
                version, platform.tag);

        String remoteFile = platform.remoteFileName(nodeVersion);
        URI archiveUri = URI.create(DIST_BASE_URL + "/v" + nodeVersion + "/" + remoteFile);
        URI checksumsUri = URI.create(DIST_BASE_URL + "/v" + nodeVersion + "/SHASUMS256.txt");
        String expectedSha256 = fetchExpectedChecksum(checksumsUri, remoteFile);

        Path tempDir = createTempDir();
        try {
            String downloadedName = remoteFile.contains("/") ? remoteFile.substring(remoteFile.lastIndexOf('/') + 1) : remoteFile;
            Path downloaded = tempDir.resolve(downloadedName);
            downloadFile(archiveUri, downloaded);

            String actualSha256 = Sha256.hex(downloaded);
            if (!actualSha256.equalsIgnoreCase(expectedSha256)) {
                throw new BridgeException(null, BridgeErrorCode.INTERNAL_ERROR,
                        "checksum do download do Node.js nao confere - o download pode estar corrompido ou adulterado (esperado="
                                + expectedSha256 + ", obtido=" + actualSha256 + ", arquivo=" + remoteFile + ")");
            }

            Files.createDirectories(installDir);
            if (platform.archiveKind == ArchiveKind.STANDALONE_EXE) {
                Files.move(downloaded, executable, StandardCopyOption.REPLACE_EXISTING);
            } else {
                extractFromTarGz(downloaded, platform, tempDir, executable);
            }
        } catch (IOException e) {
            throw new BridgeException("falha ao instalar o runtime Node.js baixado em " + installDir, e);
        } finally {
            deleteRecursively(tempDir);
        }

        if (!Files.isRegularFile(executable)) {
            throw new BridgeException(null, BridgeErrorCode.INTERNAL_ERROR,
                    "download do Node.js foi concluido mas o executavel esperado nao foi encontrado em " + executable);
        }
        markExecutable(executable);
        log.info("Node.js {} instalado em {}", version, executable);
        return new NodeExecutable(executable, version, NodeExecutable.NodeSource.DOWNLOADED);
    }

    private String fetchExpectedChecksum(URI checksumsUri, String targetFileName) {
        String body = getString(checksumsUri);
        for (String line : body.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\\s+", 2);
            if (parts.length == 2 && parts[1].strip().equals(targetFileName)) {
                return parts[0].strip();
            }
        }
        throw new BridgeException(null, BridgeErrorCode.INTERNAL_ERROR,
                "nao foi possivel localizar o checksum esperado para '" + targetFileName + "' em " + checksumsUri);
    }

    private String getString(URI uri) {
        HttpURLConnection connection = openConnection(uri);
        try {
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new BridgeException(null, BridgeErrorCode.INTERNAL_ERROR,
                        "falha ao baixar " + uri + " - HTTP " + status);
            }
            try (InputStream in = connection.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new BridgeException("falha de rede ao acessar " + uri, e);
        } finally {
            connection.disconnect();
        }
    }

    private void downloadFile(URI uri, Path destination) {
        HttpURLConnection connection = openConnection(uri);
        try {
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new BridgeException(null, BridgeErrorCode.INTERNAL_ERROR,
                        "falha ao baixar " + uri + " - HTTP " + status);
            }
            Files.createDirectories(destination.getParent());
            try (InputStream in = connection.getInputStream()) {
                Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new BridgeException("falha de rede ao baixar " + uri, e);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Plain blocking {@link HttpURLConnection} rather than {@link java.net.http.HttpClient}:
     * this only ever needs two simple sequential GETs, so there is no HTTP/2 or
     * async benefit to gain, and it avoids a JDK NIO {@code Selector} being
     * opened at all - some sandboxed/restricted environments block the loopback
     * socket pair the newer client's selector needs to initialize on Windows.
     */
    private HttpURLConnection openConnection(URI uri) {
        try {
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
            connection.setReadTimeout((int) REQUEST_TIMEOUT.toMillis());
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "angatu-whatsapp-sdk");
            return connection;
        } catch (IOException e) {
            throw new BridgeException("falha ao preparar conexao HTTPS para " + uri, e);
        }
    }

    private void extractFromTarGz(Path archive, Platform platform, Path tempDir, Path executableDestination) {
        String entryPath = "node-v" + nodeVersion + "-" + platform.tag + "/bin/node";
        Path extractDir = tempDir.resolve("extract");
        try {
            Files.createDirectories(extractDir);
        } catch (IOException e) {
            throw new BridgeException("falha ao criar diretorio temporario de extracao", e);
        }

        List<String> command = List.of("tar", "-xzf", archive.toAbsolutePath().toString(),
                "-C", extractDir.toAbsolutePath().toString(), entryPath);
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(2, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new BridgeException("comando 'tar' nao respondeu a tempo ao extrair o runtime Node.js");
            }
            if (process.exitValue() != 0) {
                throw new BridgeException("comando 'tar' falhou ao extrair o runtime Node.js (exit=" + process.exitValue()
                        + "): " + output);
            }
        } catch (IOException e) {
            throw new BridgeException(
                    "nao foi possivel executar 'tar' para extrair o runtime Node.js baixado. Em sistemas Linux/macOS o comando "
                            + "'tar' e esperado no PATH. Alternativamente, instale o Node.js manualmente (>= v"
                            + NodeVersion.MIN_SUPPORTED_MAJOR + ") e configure a propriedade de sistema 'angatu.whatsapp.node.path'.",
                    e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BridgeException("extracao do runtime Node.js interrompida", e);
        }

        Path extractedNode = extractDir.resolve(entryPath);
        if (!Files.isRegularFile(extractedNode)) {
            throw new BridgeException("extracao do runtime Node.js nao produziu o arquivo esperado: " + extractedNode);
        }
        try {
            Files.move(extractedNode, executableDestination, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BridgeException("falha ao mover executavel Node.js extraido para " + executableDestination, e);
        }
    }

    private void markExecutable(Path executable) {
        if (isWindows()) {
            return;
        }
        try {
            Set<PosixFilePermission> perms = new HashSet<>(Files.getPosixFilePermissions(executable));
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(executable, perms);
        } catch (UnsupportedOperationException | IOException e) {
            log.warn("nao foi possivel marcar {} como executavel: {}", executable, e.getMessage());
        }
    }

    private Path createTempDir() {
        try {
            Path base = RuntimeCacheDirectory.tempDirectory();
            Files.createDirectories(base);
            return Files.createTempDirectory(base, "node-download-");
        } catch (IOException e) {
            throw new BridgeException("falha ao criar diretorio temporario para download do Node.js", e);
        }
    }

    private void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.debug("falha ao remover arquivo temporario {}: {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("falha ao limpar diretorio temporario {}: {}", dir, e.getMessage());
        }
    }

    private static Platform detectCurrent() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String archName = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = archName.contains("aarch64") || archName.contains("arm64");

        if (osName.contains("win")) {
            return arm ? Platform.WINDOWS_ARM64 : Platform.WINDOWS_X64;
        }
        if (osName.contains("mac") || osName.contains("darwin")) {
            return arm ? Platform.MAC_ARM64 : Platform.MAC_X64;
        }
        if (osName.contains("linux")) {
            return arm ? Platform.LINUX_ARM64 : Platform.LINUX_X64;
        }
        throw new BridgeException("download automatico do runtime Node.js nao e suportado para os.name='" + osName
                + "' os.arch='" + archName + "'. Instale o Node.js manualmente (>= v" + NodeVersion.MIN_SUPPORTED_MAJOR
                + ") e configure a propriedade de sistema 'angatu.whatsapp.node.path'.");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private enum ArchiveKind {
        STANDALONE_EXE,
        TAR_GZ
    }

    private enum Platform {
        WINDOWS_X64("win-x64", ArchiveKind.STANDALONE_EXE),
        WINDOWS_ARM64("win-arm64", ArchiveKind.STANDALONE_EXE),
        LINUX_X64("linux-x64", ArchiveKind.TAR_GZ),
        LINUX_ARM64("linux-arm64", ArchiveKind.TAR_GZ),
        MAC_X64("darwin-x64", ArchiveKind.TAR_GZ),
        MAC_ARM64("darwin-arm64", ArchiveKind.TAR_GZ);

        final String tag;
        final ArchiveKind archiveKind;

        Platform(String tag, ArchiveKind archiveKind) {
            this.tag = tag;
            this.archiveKind = archiveKind;
        }

        String remoteFileName(String version) {
            return switch (archiveKind) {
                case STANDALONE_EXE -> tag + "/node.exe";
                case TAR_GZ -> "node-v" + version + "-" + tag + ".tar.gz";
            };
        }

        String localExecutableName() {
            return archiveKind == ArchiveKind.STANDALONE_EXE ? "node.exe" : "node";
        }
    }
}
