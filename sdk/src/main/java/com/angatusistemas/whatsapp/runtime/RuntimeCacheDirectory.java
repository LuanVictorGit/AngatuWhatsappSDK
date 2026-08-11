package com.angatusistemas.whatsapp.runtime;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Resolves the single local directory this SDK uses to cache things it
 * would otherwise have to re-fetch or re-extract on every JVM start: a
 * downloaded Node.js runtime ({@code node/}) and the bundled bridge script
 * extracted from the SDK jar ({@code bridge/}).
 *
 * <p>Override with the {@code ANGATU_WHATSAPP_HOME} environment variable
 * (useful for containers/CI where the default user-profile location is not
 * writable or should not be shared across jobs). Otherwise this follows
 * platform convention: {@code %LOCALAPPDATA%\angatu-whatsapp-sdk} on
 * Windows, {@code $XDG_CACHE_HOME/angatu-whatsapp-sdk} on Unix-likes when
 * set, else {@code ~/.angatu-whatsapp-sdk}.
 */
public final class RuntimeCacheDirectory {

    private static final String ENV_OVERRIDE = "ANGATU_WHATSAPP_HOME";
    private static final String DIR_NAME_DOTTED = ".angatu-whatsapp-sdk";
    private static final String DIR_NAME_PLAIN = "angatu-whatsapp-sdk";

    private RuntimeCacheDirectory() {
    }

    public static Path resolve() {
        String override = System.getenv(ENV_OVERRIDE);
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isBlank()) {
                return Paths.get(localAppData, DIR_NAME_PLAIN);
            }
        } else {
            String xdgCacheHome = System.getenv("XDG_CACHE_HOME");
            if (xdgCacheHome != null && !xdgCacheHome.isBlank()) {
                return Paths.get(xdgCacheHome, DIR_NAME_PLAIN);
            }
        }

        return Paths.get(System.getProperty("user.home"), DIR_NAME_DOTTED);
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
