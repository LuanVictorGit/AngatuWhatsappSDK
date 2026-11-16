package com.angatusistemas.whatsapp.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.angatusistemas.whatsapp.model.Button;
import com.angatusistemas.whatsapp.model.ButtonClick;
import com.angatusistemas.whatsapp.model.Message;
import com.angatusistemas.whatsapp.model.MessageType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Text-style buttons: the rendered menu and how a typed answer maps back to a button.
 *
 * @author Angatu Sistemas
 */
class ButtonMenusTest {

    private static final List<Button> BUTTONS = List.of(Button.of("yes", "Sim, confirmo"), Button.of("no", "Não"));

    @Test
    void rendersANumberedMenu() {
        String menu = ButtonMenus.render("Agendamento", "Confirma o horário?", "Responda com o número", BUTTONS);
        assertEquals("*Agendamento*\n\nConfirma o horário?\n\n1. Sim, confirmo\n2. Não\n\n_Responda com o número_", menu);
    }

    @Test
    void recognisesNumbersAndLabelsIgnoringCaseAccentsAndPunctuation() {
        List<String> labels = BUTTONS.stream().map(Button::label).toList();
        assertEquals(0, ButtonMenus.indexOf(labels, "1"));
        assertEquals(1, ButtonMenus.indexOf(labels, " 2) "));
        assertEquals(1, ButtonMenus.indexOf(labels, "nao!"));
        assertEquals(0, ButtonMenus.indexOf(labels, "SIM, CONFIRMO."));
        assertEquals(-1, ButtonMenus.indexOf(labels, "talvez"));
    }

    @Test
    void matchesAReplyOnceAgainstTheLatestMenuOfTheChat() {
        String instance = "menu-" + UUID.randomUUID();
        String chat = "5511988887777@s.whatsapp.net";
        ButtonMenus.remember(instance, chat, "MENU-1", BUTTONS);
        Optional<ButtonClick> click = ButtonMenus.match(reply(instance, chat, "2"));
        assertTrue(click.isPresent());
        assertEquals("no", click.get().buttonId());
        assertEquals("MENU-1", click.get().menuMessageId());
        assertTrue(ButtonMenus.match(reply(instance, chat, "2")).isEmpty());
    }

    private static Message reply(String instance, String chat, String text) {
        return new Message(instance, UUID.randomUUID().toString(), chat, null, chat, null, Instant.now(), text, MessageType.TEXT,
                false, false, "Cliente", null, null, false, null, null);
    }
}
