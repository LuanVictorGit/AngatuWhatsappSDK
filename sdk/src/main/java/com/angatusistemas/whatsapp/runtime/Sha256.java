package com.angatusistemas.whatsapp.runtime;

import com.angatusistemas.whatsapp.exception.BridgeException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Small shared helper so the two runtime-cache installers do not duplicate digest boilerplate. */
final class Sha256 {

    private Sha256() {
    }

    static String hex(byte[] data) {
        return HexFormat.of().formatHex(newDigest().digest(data));
    }

    static String hex(Path file) {
        MessageDigest digest = newDigest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new BridgeException("falha ao calcular checksum de " + file, e);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 nao disponivel nesta JVM", e);
        }
    }
}
