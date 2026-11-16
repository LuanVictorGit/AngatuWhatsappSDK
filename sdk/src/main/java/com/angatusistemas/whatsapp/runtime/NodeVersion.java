package com.angatusistemas.whatsapp.runtime;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Node.js version as printed by {@code node --version}, and whether it can run the bridge.
 *
 * @author Angatu Sistemas
 */
public record NodeVersion(int major, int minor, int patch) implements Comparable<NodeVersion> {

    public static final int MIN_SUPPORTED_MAJOR = 22;

    private static final Pattern PATTERN = Pattern.compile("v?(\\d+)\\.(\\d+)\\.(\\d+)");

    public static Optional<NodeVersion> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        Matcher matcher = PATTERN.matcher(raw.strip());
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(new NodeVersion(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3))));
    }

    public boolean isSupported() {
        return major >= MIN_SUPPORTED_MAJOR;
    }

    @Override
    public int compareTo(NodeVersion other) {
        int byMajor = Integer.compare(major, other.major);
        if (byMajor != 0) {
            return byMajor;
        }
        int byMinor = Integer.compare(minor, other.minor);
        return byMinor != 0 ? byMinor : Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return "v" + major + "." + minor + "." + patch;
    }
}
