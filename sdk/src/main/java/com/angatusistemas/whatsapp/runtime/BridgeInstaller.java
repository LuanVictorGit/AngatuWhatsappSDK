package com.angatusistemas.whatsapp.runtime;

import com.angatusistemas.whatsapp.exception.BridgeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * Extracts the bundled {@code bridge.mjs} (built by {@code bridge/build.mjs}
 * and embedded as a classpath resource inside the SDK jar) into the local
 * runtime cache so it can be handed to {@code node} as a plain file path -
 * a script cannot be executed directly out of a jar.
 *
 * <p>The install directory is named after the script's own SHA-256, so
 * upgrading the SDK to a jar with a different bridge build automatically
 * installs alongside (rather than over) any previous version's cache entry,
 * and re-running against an unchanged jar is a no-op after the first call.
 */
public final class BridgeInstaller {

    private static final Logger log = LoggerFactory.getLogger(BridgeInstaller.class);

    private static final String SCRIPT_RESOURCE = "/bridge/bridge.mjs";
    private static final String BUILD_INFO_RESOURCE = "/bridge/BUILD_INFO.json";

    public Path install() {
        byte[] script = readClasspathResource(SCRIPT_RESOURCE);
        String contentHash = Sha256.hex(script);
        Path targetDir = RuntimeCacheDirectory.bridgeDirectory().resolve(contentHash);
        Path targetScript = targetDir.resolve("bridge.mjs");

        if (alreadyInstalled(targetScript, script)) {
            log.debug("bridge.mjs ja instalado em {}", targetScript);
            return targetScript;
        }

        try {
            Files.createDirectories(targetDir);
            Path tempFile = Files.createTempFile(targetDir, "bridge", ".mjs.tmp");
            Files.write(tempFile, script);
            Files.move(tempFile, targetScript, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new BridgeException("falha ao instalar o script do bridge em " + targetScript, e);
        }

        installBuildInfoBestEffort(targetDir);
        log.info("bridge.mjs instalado em {}", targetScript);
        return targetScript;
    }

    private boolean alreadyInstalled(Path targetScript, byte[] expectedContent) {
        if (!Files.isRegularFile(targetScript)) {
            return false;
        }
        try {
            return Arrays.equals(expectedContent, Files.readAllBytes(targetScript));
        } catch (IOException e) {
            log.debug("nao foi possivel verificar instalacao existente de bridge.mjs, reinstalando: {}", e.getMessage());
            return false;
        }
    }

    private void installBuildInfoBestEffort(Path targetDir) {
        try (InputStream in = BridgeInstaller.class.getResourceAsStream(BUILD_INFO_RESOURCE)) {
            if (in == null) {
                return;
            }
            Files.write(targetDir.resolve("BUILD_INFO.json"), in.readAllBytes());
        } catch (IOException e) {
            log.debug("nao foi possivel copiar BUILD_INFO.json para o cache local: {}", e.getMessage());
        }
    }

    private byte[] readClasspathResource(String path) {
        try (InputStream in = BridgeInstaller.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new BridgeException("recurso '" + path + "' nao encontrado no classpath - o jar do SDK parece "
                        + "ter sido construido sem o bridge embutido (execute o build do modulo 'sdk' sem "
                        + "-Dskip.bridge.build=true, ou verifique bridge/build.mjs)");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new BridgeException("falha ao ler recurso '" + path + "' do classpath", e);
        }
    }
}
