package com.angatusistemas.whatsapp.protocol;

/**
 * Only the major component needs to match between Java and the bridge;
 * minor additions on either side must stay additive/optional. Mirrors
 * {@code isProtocolCompatible} in {@code bridge/src/protocol/messages.ts}.
 */
public final class ProtocolVersion {

    public static final String CURRENT = "1.0";

    private ProtocolVersion() {
    }

    public static boolean isCompatible(String remoteVersion) {
        if (remoteVersion == null || remoteVersion.isBlank()) {
            return false;
        }
        return major(remoteVersion).equals(major(CURRENT));
    }

    private static String major(String version) {
        int dot = version.indexOf('.');
        return dot >= 0 ? version.substring(0, dot) : version;
    }
}
