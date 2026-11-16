package com.angatusistemas.whatsapp.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Parsing of the command line options.
 *
 * @author Angatu Sistemas
 */
class CliArgumentsTest {

    @Test
    void readsNamedOptions() {
        CliArguments options = CliArguments.parse(new String[] {"--instance-id", "loja", "--session-dir", "/data/wa"});
        assertEquals("loja", options.require("instance-id"));
        assertTrue(options.optional("pairing-phone").isEmpty());
    }

    @Test
    void refusesALooseValueOrAMissingOne() {
        assertThrows(IllegalArgumentException.class, () -> CliArguments.parse(new String[] {"loja"}));
        assertThrows(IllegalArgumentException.class, () -> CliArguments.parse(new String[] {"--to"}));
        assertThrows(IllegalArgumentException.class, () -> CliArguments.parse(new String[0]).require("to"));
    }
}
