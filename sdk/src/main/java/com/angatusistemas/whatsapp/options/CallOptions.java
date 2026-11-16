package com.angatusistemas.whatsapp.options;

import java.time.Duration;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * What to do when a call rings: by default nothing (listeners are still told). With rejectCalls the
 * call is declined after a random delay, the way a person glances at the phone first, and an
 * optional text is sent to the caller through the normal queue.
 *
 * @author Angatu Sistemas
 */
public record CallOptions(boolean rejectCalls, Duration rejectDelayMin, Duration rejectDelayMax, String replyText) {

    public CallOptions {
        Objects.requireNonNull(rejectDelayMin, "rejectDelayMin");
        Objects.requireNonNull(rejectDelayMax, "rejectDelayMax");
        if (rejectDelayMin.isNegative() || rejectDelayMax.compareTo(rejectDelayMin) < 0) {
            throw new IllegalArgumentException("reject delay must satisfy 0 <= min <= max");
        }
        if (replyText != null && replyText.isBlank()) {
            replyText = null;
        }
    }

    public static CallOptions defaults() {
        return new CallOptions(false, Duration.ofSeconds(2), Duration.ofSeconds(5), null);
    }

    public static CallOptions rejectAll() {
        return defaults().withRejectCalls(true);
    }

    public CallOptions withRejectCalls(boolean reject) {
        return new CallOptions(reject, rejectDelayMin, rejectDelayMax, replyText);
    }

    public CallOptions withRejectDelay(Duration min, Duration max) {
        return new CallOptions(rejectCalls, min, max, replyText);
    }

    public CallOptions withReply(String text) {
        return new CallOptions(rejectCalls, rejectDelayMin, rejectDelayMax, text);
    }

    public Duration rejectDelay(RandomGenerator random) {
        long min = rejectDelayMin.toMillis();
        long max = rejectDelayMax.toMillis();
        return Duration.ofMillis(max <= min ? min : random.nextLong(min, max + 1));
    }
}
