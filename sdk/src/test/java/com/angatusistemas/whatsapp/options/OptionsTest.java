package com.angatusistemas.whatsapp.options;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

/**
 * Validation and derived timings of the pacing, humanization and call options.
 *
 * @author Angatu Sistemas
 */
class OptionsTest {

    private static final RandomGenerator RANDOM = RandomGenerator.of("L64X128MixRandom");

    @Test
    void refusesAnIntervalUnderFiveHundredMilliseconds() {
        assertThrows(IllegalArgumentException.class,
                () -> PacingOptions.defaults().withInteractionInterval(Duration.ofMillis(499)));
        assertEquals(Duration.ofMillis(500), PacingOptions.defaults().interactionInterval());
    }

    @Test
    void keepsTheSendGapInsideItsBounds() {
        PacingOptions pacing = PacingOptions.defaults().withGap(Duration.ofSeconds(2), Duration.ofSeconds(3));
        for (int i = 0; i < 200; i++) {
            long gap = pacing.nextGap(RANDOM).toMillis();
            assertTrue(gap >= 2_000 && gap <= 3_000, "gap " + gap);
        }
    }

    @Test
    void typingIsProportionalToTheTextAndBounded() {
        HumanizationOptions signals = HumanizationOptions.defaults().withTyping(Duration.ofSeconds(1), Duration.ofSeconds(4)).withTypingSpeed(50);
        long shortText = signals.typingDuration(2, RANDOM).toMillis();
        long longText = signals.typingDuration(10_000, RANDOM).toMillis();
        assertTrue(shortText >= 1_000 && shortText <= 1_250, "short " + shortText);
        assertTrue(longText >= 4_000 && longText <= 5_000, "long " + longText);
        assertEquals(Duration.ZERO, signals.withoutTyping().typingDuration(100, RANDOM));
    }

    @Test
    void callRejectionDelayStaysInItsWindow() {
        CallOptions calls = CallOptions.rejectAll().withRejectDelay(Duration.ofSeconds(1), Duration.ofSeconds(2)).withReply("  ");
        assertEquals(null, calls.replyText());
        for (int i = 0; i < 100; i++) {
            long delay = calls.rejectDelay(RANDOM).toMillis();
            assertTrue(delay >= 1_000 && delay <= 2_000);
        }
    }
}
