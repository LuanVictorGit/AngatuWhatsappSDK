package com.angatusistemas.whatsapp.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Turning phone numbers into WhatsApp addresses.
 *
 * @author Angatu Sistemas
 */
class JidsTest {

    @Test
    void buildsAJidFromAFormattedNumber() {
        assertEquals("5562999998888@s.whatsapp.net", Jids.of("+55 (62) 99999-8888"));
    }

    @Test
    void keepsAnExistingJid() {
        assertEquals("1203630@g.us", Jids.of("1203630@g.us"));
        assertTrue(Jids.isGroup("1203630@g.us"));
    }

    @Test
    void refusesWhatIsNotANumber() {
        assertThrows(IllegalArgumentException.class, () -> Jids.of("abc"));
    }
}
