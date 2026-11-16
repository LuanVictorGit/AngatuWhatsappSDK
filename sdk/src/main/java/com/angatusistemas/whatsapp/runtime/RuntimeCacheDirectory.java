package com.angatusistemas.whatsapp.runtime;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Where the SDK keeps what it downloads and extracts (Node.js, the bridge script). Point
 * ANGATU_WHATSAPP_HOME at a persistent volume in a container, or every deploy downloads Node again.
 *
 * @author Angatu Sistemas
 */
public final class RuntimeCacheDirectory {

    private static final String ENV_OVERRIDE = "ANGATU_WHATSAPP_HOME";

    private RuntimeCacheDirectory() {
    }

    public static Path resolve() {
        String override = System.getenv(ENV_OVERRIDE);
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String base = windows ? System.getenv("LOCALAPPDATA") : System.getenv("XDG_CACHE_HOME");
        if (base != null && !base.isBlank()) {
            return Path.of(base, "angatu-whatsapp-sdk");
        }
        return Path.of(System.getProperty("user.home"), ".angatu-whatsapp-sdk");
    }

    public static Path nodeDirectory() {
        return resolve().resolve("node");
    }

    public static Path bridgeDirectory() {
        return resolve().resolve("bridge");
    }

    public static Path tempDirectory() {
        return resolve().resolve("tmp");
    }
}
