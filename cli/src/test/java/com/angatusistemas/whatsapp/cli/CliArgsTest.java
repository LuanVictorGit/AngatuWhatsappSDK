package com.angatusistemas.whatsapp.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CliArgsTest {

    @Test
    void parsesRequiredAndOptionalFlags() {
        CliArgs args = CliArgs.parse(new String[] {"--instance-id", "bot-1", "--session-dir", "/tmp/session"});

        assertEquals("bot-1", args.require("instance-id"));
        assertEquals("/tmp/session", args.require("session-dir"));
        assertEquals("default-name", args.optional("display-name", "default-name"));
    }

    @Test
    void requireThrowsWhenFlagIsMissing() {
        CliArgs args = CliArgs.parse(new String[] {"--instance-id", "bot-1"});

        assertThrows(CliArgumentException.class, () -> args.require("session-dir"));
    }

    @Test
    void parseThrowsWhenTokenDoesNotStartWithDoubleDash() {
        assertThrows(CliArgumentException.class, () -> CliArgs.parse(new String[] {"instance-id", "bot-1"}));
    }

    @Test
    void parseThrowsWhenFlagIsMissingItsValue() {
        assertThrows(CliArgumentException.class, () -> CliArgs.parse(new String[] {"--instance-id"}));
    }

    @Test
    void laterOccurrenceOfAFlagWins() {
        CliArgs args = CliArgs.parse(new String[] {"--to", "111", "--to", "222"});

        assertEquals("222", args.require("to"));
    }
}
