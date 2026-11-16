package com.angatusistemas.whatsapp.request;

import com.angatusistemas.whatsapp.model.Button;
import com.angatusistemas.whatsapp.model.ButtonStyle;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A message with reply buttons. NATIVE renders up to three interactive buttons; TEXT renders a
 * numbered menu of up to ten options that any client shows. Either way the choice arrives on the
 * ButtonListener with the button id.
 *
 * @author Angatu Sistemas
 */
public record SendButtonsRequest(
        String to,
        String text,
        String title,
        String footer,
        List<Button> buttons,
        ButtonStyle style,
        String quotedMessageId) {

    public static final int MAX_NATIVE_BUTTONS = 3;
    public static final int MAX_TEXT_BUTTONS = 10;

    public SendButtonsRequest {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(style, "style");
        buttons = List.copyOf(Objects.requireNonNull(buttons, "buttons"));
        int max = style == ButtonStyle.NATIVE ? MAX_NATIVE_BUTTONS : MAX_TEXT_BUTTONS;
        if (buttons.isEmpty() || buttons.size() > max) {
            throw new IllegalArgumentException("between 1 and " + max + " buttons are allowed for " + style);
        }
        Set<String> ids = new HashSet<>();
        for (Button button : buttons) {
            if (!ids.add(button.id())) {
                throw new IllegalArgumentException("duplicate button id: " + button.id());
            }
        }
    }

    public static SendButtonsRequest of(String to, String text, Button... buttons) {
        return new SendButtonsRequest(to, text, null, null, List.of(buttons), ButtonStyle.NATIVE, null);
    }

    public SendButtonsRequest withTitle(String value) {
        return new SendButtonsRequest(to, text, value, footer, buttons, style, quotedMessageId);
    }

    public SendButtonsRequest withFooter(String value) {
        return new SendButtonsRequest(to, text, title, value, buttons, style, quotedMessageId);
    }

    public SendButtonsRequest withStyle(ButtonStyle value) {
        return new SendButtonsRequest(to, text, title, footer, buttons, value, quotedMessageId);
    }

    public SendButtonsRequest quoting(String messageId) {
        return new SendButtonsRequest(to, text, title, footer, buttons, style, messageId);
    }
}
