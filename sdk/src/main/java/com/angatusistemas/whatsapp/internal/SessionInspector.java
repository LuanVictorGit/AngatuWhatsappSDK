package com.angatusistemas.whatsapp.internal;

import com.angatusistemas.whatsapp.model.SessionStatus;
import com.angatusistemas.whatsapp.model.SessionStatus.State;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * Reads the credentials a session directory holds, without connecting, to tell whether the account
 * will resume after a restart. A session is paired only when it has both its identity (me) and the
 * signed device account WhatsApp issues on a completed pairing; an identity without the account is
 * a pairing that was started and never finished.
 *
 * @author Angatu Sistemas
 */
public final class SessionInspector {

    public static final String CREDENTIALS_FILE = "creds.json";
    public static final String BACKUP_FILE = "creds.backup.json";

    private SessionInspector() {
    }

    public static SessionStatus inspect(Path sessionDirectory) {
        boolean backup = Files.isRegularFile(sessionDirectory.resolve(BACKUP_FILE));
        JsonObject creds;
        try {
            creds = read(sessionDirectory.resolve(CREDENTIALS_FILE));
        } catch (NoSuchFileException e) {
            return new SessionStatus(backup ? State.CORRUPTED : State.NOT_PAIRED, null, backup);
        } catch (IOException | JsonParseException | IllegalStateException e) {
            return new SessionStatus(State.CORRUPTED, null, backup);
        }
        JsonObject me = Json.object(creds, "me");
        String id = Json.str(me, "id");
        if (id == null) {
            return new SessionStatus(State.NOT_PAIRED, null, backup);
        }
        String phone = id.split("[:@]", 2)[0].replaceAll("\\D", "");
        State state = creds.has("account") && !creds.get("account").isJsonNull() ? State.PAIRED : State.PAIRING_INCOMPLETE;
        return new SessionStatus(state, phone.isEmpty() ? null : phone, backup);
    }

    private static JsonObject read(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
