package com.angatusistemas.whatsapp.runtime;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NodeVersion#parse(String)} is the sole gate deciding whether a
 * discovered {@code node} binary on the user's machine is usable, so its
 * edge cases (missing {@code v} prefix, surrounding banner text, garbage
 * input) matter as much as the happy path.
 */
class NodeVersionTest {

    @Test
    void parsesVersionWithLeadingV() {
        NodeVersion version = NodeVersion.parse("v24.19.0").orElseThrow();

        assertEquals(24, version.major());
        assertEquals(19, version.minor());
        assertEquals(0, version.patch());
    }

    @Test
    void parsesVersionWithoutLeadingV() {
        NodeVersion version = NodeVersion.parse("24.19.0").orElseThrow();

        assertEquals(new NodeVersion(24, 19, 0), version);
    }

    @Test
    void parsesVersionEmbeddedInBannerText() {
        NodeVersion version = NodeVersion.parse("Node.js v24.19.0 (Krypton)\n").orElseThrow();

        assertEquals(new NodeVersion(24, 19, 0), version);
    }

    @Test
    void parseReturnsEmptyForNull() {
        assertEquals(Optional.empty(), NodeVersion.parse(null));
    }

    @Test
    void parseReturnsEmptyForTextWithoutAVersion() {
        assertEquals(Optional.empty(), NodeVersion.parse("not a version"));
    }

    @Test
    void parseReturnsEmptyForIncompleteVersion() {
        assertEquals(Optional.empty(), NodeVersion.parse("v24.19"));
    }

    @Test
    void satisfiesMinimumIsTrueAtAndAboveFloor() {
        assertTrue(new NodeVersion(NodeVersion.MIN_SUPPORTED_MAJOR, 0, 0).satisfiesMinimum());
        assertTrue(new NodeVersion(24, 19, 0).satisfiesMinimum());
    }

    @Test
    void satisfiesMinimumIsFalseBelowFloor() {
        assertFalse(new NodeVersion(NodeVersion.MIN_SUPPORTED_MAJOR - 1, 99, 99).satisfiesMinimum());
    }

    @Test
    void compareToOrdersByMajorThenMinorThenPatch() {
        assertTrue(new NodeVersion(20, 0, 0).compareTo(new NodeVersion(20, 0, 1)) < 0);
        assertTrue(new NodeVersion(20, 0, 1).compareTo(new NodeVersion(20, 1, 0)) < 0);
        assertTrue(new NodeVersion(20, 1, 0).compareTo(new NodeVersion(21, 0, 0)) < 0);
        assertEquals(0, new NodeVersion(24, 19, 0).compareTo(new NodeVersion(24, 19, 0)));
    }

    @Test
    void toStringUsesNodeVersionFormat() {
        assertEquals("v24.19.0", new NodeVersion(24, 19, 0).toString());
    }
}
