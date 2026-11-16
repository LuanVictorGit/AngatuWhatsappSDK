package com.angatusistemas.whatsapp.internal;

import java.util.regex.Pattern;

/**
 * Builds WhatsApp addresses: a bare phone number becomes {@code <digits>@s.whatsapp.net}, anything
 * that already has a server part is kept as it is.
 *
 * @author Angatu Sistemas
 */
public final class Jids {

    private static final Pattern NOT_DIGIT = Pattern.compile("\\D");

    private Jids() {
    }

    public static String of(String numberOrJid) {
        if (numberOrJid == null) {
            throw new IllegalArgumentException("a phone number or JID is required");
        }
        if (numberOrJid.indexOf('@') >= 0) {
            return numberOrJid.strip();
        }
        String digits = digits(numberOrJid);
        if (digits.length() < 8 || digits.length() > 15) {
            throw new IllegalArgumentException("not a phone number with country code: " + numberOrJid);
        }
        return digits + "@s.whatsapp.net";
    }

    public static String digits(String value) {
        return NOT_DIGIT.matcher(value).replaceAll("");
    }

    public static boolean isGroup(String jid) {
        return jid != null && jid.endsWith("@g.us");
    }
}
