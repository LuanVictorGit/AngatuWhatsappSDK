package com.angatusistemas.whatsapp.runtime;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed {@code major.minor.patch} Node.js version, as reported by
 * {@code node --version} (which prints a leading {@code v}, e.g.
 * {@code v24.19.0}).
 */
public record NodeVersion(int major, int minor, int patch) implements Comparable<NodeVersion> {

    /** Mirrors the {@code engines.node} constraint in {@code bridge/package.json}. */
    public static final int MIN_SUPPORTED_MAJOR = 20;

    private static final Pattern PATTERN = Pattern.compile("v?(\\d+)\\.(\\d+)\\.(\\d+)");

    public static Optional<NodeVersion> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        Matcher matcher = PATTERN.matcher(raw.strip());
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(new NodeVersion(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3))));
    }

    public boolean satisfiesMinimum() {
        return major >= MIN_SUPPORTED_MAJOR;
    }

    @Override
    public int compareTo(NodeVersion other) {
        int c = Integer.compare(major, other.major);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(minor, other.minor);
        if (c != 0) {
            return c;
        }
        return Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return "v" + major + "." + minor + "." + patch;
    }
}
