package com.angatusistemas.whatsapp.options;

import java.time.Duration;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * The signals the account shows around its interactions: "typing..." for a time proportional to
 * the text (bounded by typingMin and typingMax), "recording..." before a voice note, read receipts,
 * and whether it stays online all the time or goes offline some time after its last send.
 *
 * @author Angatu Sistemas
 */
public record HumanizationOptions(
        boolean typing,
        Duration typingMin,
        Duration typingMax,
        int typingMillisPerChar,
        ReadReceiptMode readReceipts,
        Duration readDelayMin,
        Duration readDelayMax,
        boolean alwaysOnline,
        Duration onlineHoldAfterSend,
        boolean autoSubscribePresence) {

    public HumanizationOptions {
        Objects.requireNonNull(typingMin, "typingMin");
        Objects.requireNonNull(typingMax, "typingMax");
        Objects.requireNonNull(readReceipts, "readReceipts");
        Objects.requireNonNull(readDelayMin, "readDelayMin");
        Objects.requireNonNull(readDelayMax, "readDelayMax");
        Objects.requireNonNull(onlineHoldAfterSend, "onlineHoldAfterSend");
        if (typingMin.isNegative() || typingMax.compareTo(typingMin) < 0) {
            throw new IllegalArgumentException("typing must satisfy 0 <= typingMin <= typingMax");
        }
        if (readDelayMin.isNegative() || readDelayMax.compareTo(readDelayMin) < 0) {
            throw new IllegalArgumentException("read delay must satisfy 0 <= min <= max");
        }
        if (typingMillisPerChar < 0 || onlineHoldAfterSend.isNegative()) {
            throw new IllegalArgumentException("typing speed and online hold must not be negative");
        }
    }

    public static HumanizationOptions defaults() {
        return new HumanizationOptions(true, Duration.ofSeconds(3), Duration.ofSeconds(9), 25,
                ReadReceiptMode.ON_REPLY, Duration.ofSeconds(1), Duration.ofSeconds(4),
                true, Duration.ofSeconds(30), false);
    }

    public static HumanizationOptions off() {
        return new HumanizationOptions(false, Duration.ZERO, Duration.ZERO, 0,
                ReadReceiptMode.MANUAL, Duration.ZERO, Duration.ZERO,
                false, Duration.ZERO, false);
    }

    public HumanizationOptions withTyping(Duration min, Duration max) {
        return new HumanizationOptions(true, min, max, typingMillisPerChar, readReceipts, readDelayMin, readDelayMax,
                alwaysOnline, onlineHoldAfterSend, autoSubscribePresence);
    }

    public HumanizationOptions withTypingSpeed(int millisPerChar) {
        return new HumanizationOptions(typing, typingMin, typingMax, millisPerChar, readReceipts, readDelayMin, readDelayMax,
                alwaysOnline, onlineHoldAfterSend, autoSubscribePresence);
    }

    public HumanizationOptions withoutTyping() {
        return new HumanizationOptions(false, typingMin, typingMax, typingMillisPerChar, readReceipts, readDelayMin, readDelayMax,
                alwaysOnline, onlineHoldAfterSend, autoSubscribePresence);
    }

    public HumanizationOptions withReadReceipts(ReadReceiptMode mode) {
        return new HumanizationOptions(typing, typingMin, typingMax, typingMillisPerChar, mode, readDelayMin, readDelayMax,
                alwaysOnline, onlineHoldAfterSend, autoSubscribePresence);
    }

    public HumanizationOptions withReadDelay(Duration min, Duration max) {
        return new HumanizationOptions(typing, typingMin, typingMax, typingMillisPerChar, readReceipts, min, max,
                alwaysOnline, onlineHoldAfterSend, autoSubscribePresence);
    }

    public HumanizationOptions withAlwaysOnline(boolean online) {
        return new HumanizationOptions(typing, typingMin, typingMax, typingMillisPerChar, readReceipts, readDelayMin, readDelayMax,
                online, onlineHoldAfterSend, autoSubscribePresence);
    }

    public HumanizationOptions withOnlineHoldAfterSend(Duration hold) {
        return new HumanizationOptions(typing, typingMin, typingMax, typingMillisPerChar, readReceipts, readDelayMin, readDelayMax,
                alwaysOnline, hold, autoSubscribePresence);
    }

    public HumanizationOptions withAutoSubscribePresence(boolean subscribe) {
        return new HumanizationOptions(typing, typingMin, typingMax, typingMillisPerChar, readReceipts, readDelayMin, readDelayMax,
                alwaysOnline, onlineHoldAfterSend, subscribe);
    }

    public Duration typingDuration(int textLength, RandomGenerator random) {
        if (!typing || typingMax.isZero()) {
            return Duration.ZERO;
        }
        long proportional = (long) Math.max(0, textLength) * typingMillisPerChar;
        long bounded = Math.min(typingMax.toMillis(), Math.max(typingMin.toMillis(), proportional));
        // A quarter of jitter: two messages of the same size never take exactly as long to type.
        long jitter = bounded > 4 ? random.nextLong(bounded / 4) : 0;
        return Duration.ofMillis(bounded + jitter);
    }

    public Duration readDelay(RandomGenerator random) {
        long min = readDelayMin.toMillis();
        long max = readDelayMax.toMillis();
        return Duration.ofMillis(max <= min ? min : random.nextLong(min, max + 1));
    }
}
