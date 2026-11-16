package com.angatusistemas.whatsapp.queue;

import br.com.angatusistemas.lib.database.Saveable;
import com.angatusistemas.whatsapp.model.Button;
import com.angatusistemas.whatsapp.model.ButtonClick;
import com.angatusistemas.whatsapp.model.Message;
import java.text.Normalizer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text-style buttons: renders a numbered menu, remembers it per chat in SQLite, and recognises a
 * reply that picks an option by its number ("2", "2.", "2)") or by its label, ignoring case and
 * accents. A menu answers once and expires after a day.
 *
 * @author Angatu Sistemas
 */
public final class ButtonMenus {

    private static final Duration LIFETIME = Duration.ofHours(24);
    private static final int MAX_REPLY_LENGTH = 60;
    private static final Pattern NUMBER = Pattern.compile("^\\s*(\\d{1,2})\\s*[.)\\-:]?\\s*$");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern TRAILING = Pattern.compile("[\\s.!?]+$");
    private static final String TABLE = Saveable.tableName(ButtonMenu.class);
    private static final String LATEST = "SELECT data FROM " + TABLE
            + " WHERE json_extract(data,'$.instanceId') = ? AND json_extract(data,'$.chatId') = ?"
            + " AND json_extract(data,'$.expiresAt') > ? ORDER BY id DESC LIMIT 1";

    private ButtonMenus() {
    }

    public static String render(String title, String text, String footer, List<Button> buttons) {
        StringBuilder menu = new StringBuilder(text.length() + buttons.size() * 24 + 32);
        if (title != null && !title.isBlank()) {
            menu.append('*').append(title.strip()).append("*\n\n");
        }
        menu.append(text.strip()).append("\n");
        for (int i = 0; i < buttons.size(); i++) {
            menu.append('\n').append(i + 1).append(". ").append(buttons.get(i).label());
        }
        if (footer != null && !footer.isBlank()) {
            menu.append("\n\n_").append(footer.strip()).append('_');
        }
        return menu.toString();
    }

    public static void remember(String instanceId, String chatId, String messageId, List<Button> buttons) {
        InteractionStore.ensureIndexes();
        ButtonMenu menu = new ButtonMenu();
        menu.setId(messageId);
        menu.setInstanceId(instanceId);
        menu.setChatId(chatId);
        menu.setButtonIds(buttons.stream().map(Button::id).toList());
        menu.setLabels(buttons.stream().map(Button::label).toList());
        menu.setExpiresAt(System.currentTimeMillis() + LIFETIME.toMillis());
        menu.save();
    }

    public static Optional<ButtonClick> match(Message reply) {
        if (reply.isFromMe() || !reply.hasText() || reply.text().length() > MAX_REPLY_LENGTH) {
            return Optional.empty();
        }
        InteractionStore.ensureIndexes();
        Optional<ButtonMenu> latest = Saveable.query(ButtonMenu.class, LATEST, reply.instanceId(), reply.chatId(), System.currentTimeMillis())
                .stream().findFirst();
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        ButtonMenu menu = latest.get();
        int index = indexOf(menu.getLabels(), reply.text());
        if (index < 0 || index >= menu.getButtonIds().size()) {
            return Optional.empty();
        }
        menu.delete();
        return Optional.of(new ButtonClick(menu.getButtonIds().get(index), menu.getLabels().get(index), menu.getId(), reply));
    }

    static int indexOf(List<String> labels, String text) {
        Matcher number = NUMBER.matcher(text);
        if (number.matches()) {
            return Integer.parseInt(number.group(1)) - 1;
        }
        String wanted = normalize(text);
        for (int i = 0; i < labels.size(); i++) {
            if (normalize(labels.get(i)).equals(wanted)) {
                return i;
            }
        }
        return -1;
    }

    private static String normalize(String value) {
        String decomposed = Normalizer.normalize(value.strip(), Normalizer.Form.NFD);
        return TRAILING.matcher(MARKS.matcher(decomposed).replaceAll("").toLowerCase(Locale.ROOT)).replaceAll("");
    }
}
