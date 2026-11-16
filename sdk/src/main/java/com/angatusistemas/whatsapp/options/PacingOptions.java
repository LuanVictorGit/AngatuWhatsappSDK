package com.angatusistemas.whatsapp.options;

import java.time.Duration;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * The rhythm of the interaction queue. Every operation waits at least {@code interactionInterval}
 * (never under 500 ms) after the previous one; messages are additionally spaced by a random gap
 * and capped per minute, and queries are capped per minute. These limits are what keeps a number
 * from looking automated: raising them because a broadcast feels slow is the fastest way to get it
 * banned, and no pacing protects unsolicited content sent to strangers.
 *
 * @author Angatu Sistemas
 */
public record PacingOptions(
        Duration minGap,
        Duration maxGap,
        int maxSendsPerMinute,
        int maxReadsPerMinute,
        Duration interactionInterval,
        Duration maxPendingAge) {

    public static final Duration MIN_INTERACTION_INTERVAL = Duration.ofMillis(500);

    public PacingOptions {
        Objects.requireNonNull(minGap, "minGap");
        Objects.requireNonNull(maxGap, "maxGap");
        Objects.requireNonNull(interactionInterval, "interactionInterval");
        Objects.requireNonNull(maxPendingAge, "maxPendingAge");
        if (minGap.isNegative() || maxGap.compareTo(minGap) < 0) {
            throw new IllegalArgumentException("gap must satisfy 0 <= minGap <= maxGap");
        }
        if (maxSendsPerMinute < 1 || maxReadsPerMinute < 1) {
            throw new IllegalArgumentException("per-minute caps must be at least 1");
        }
        if (interactionInterval.compareTo(MIN_INTERACTION_INTERVAL) < 0) {
            throw new IllegalArgumentException("interactionInterval must be at least " + MIN_INTERACTION_INTERVAL.toMillis() + " ms");
        }
        if (maxPendingAge.isNegative() || maxPendingAge.isZero()) {
            throw new IllegalArgumentException("maxPendingAge must be positive");
        }
    }

    public static PacingOptions defaults() {
        return new PacingOptions(Duration.ofSeconds(4), Duration.ofSeconds(12), 10, 30, MIN_INTERACTION_INTERVAL, Duration.ofHours(24));
    }

    public PacingOptions withGap(Duration min, Duration max) {
        return new PacingOptions(min, max, maxSendsPerMinute, maxReadsPerMinute, interactionInterval, maxPendingAge);
    }

    public PacingOptions withMaxSendsPerMinute(int max) {
        return new PacingOptions(minGap, maxGap, max, maxReadsPerMinute, interactionInterval, maxPendingAge);
    }

    public PacingOptions withMaxReadsPerMinute(int max) {
        return new PacingOptions(minGap, maxGap, maxSendsPerMinute, max, interactionInterval, maxPendingAge);
    }

    public PacingOptions withInteractionInterval(Duration interval) {
        return new PacingOptions(minGap, maxGap, maxSendsPerMinute, maxReadsPerMinute, interval, maxPendingAge);
    }

    public PacingOptions withMaxPendingAge(Duration age) {
        return new PacingOptions(minGap, maxGap, maxSendsPerMinute, maxReadsPerMinute, interactionInterval, age);
    }

    public Duration nextGap(RandomGenerator random) {
        long min = minGap.toMillis();
        long max = maxGap.toMillis();
        return Duration.ofMillis(max <= min ? min : random.nextLong(min, max + 1));
    }
}
