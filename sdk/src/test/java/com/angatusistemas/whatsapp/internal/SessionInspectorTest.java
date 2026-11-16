package com.angatusistemas.whatsapp.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.angatusistemas.whatsapp.model.SessionStatus;
import com.angatusistemas.whatsapp.model.SessionStatus.State;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reading a session directory before connecting: paired, half-paired, empty and corrupted.
 *
 * @author Angatu Sistemas
 */
class SessionInspectorTest {

    @TempDir
    Path session;

    @Test
    void emptyDirectoryIsNotPaired() {
        assertEquals(State.NOT_PAIRED, SessionInspector.inspect(session).state());
    }

    @Test
    void identityWithSignedAccountIsPaired() throws IOException {
        Files.writeString(session.resolve("creds.json"),
                "{\"me\":{\"id\":\"5562999998888:12@s.whatsapp.net\"},\"account\":{\"details\":\"x\"}}");
        SessionStatus status = SessionInspector.inspect(session);
        assertEquals(State.PAIRED, status.state());
        assertEquals("5562999998888", status.phoneNumber());
        assertTrue(status.canResume());
    }

    @Test
    void identityWithoutAccountIsAnUnfinishedPairing() throws IOException {
        Files.writeString(session.resolve("creds.json"), "{\"me\":{\"id\":\"5562999998888@s.whatsapp.net\"}}");
        assertEquals(State.PAIRING_INCOMPLETE, SessionInspector.inspect(session).state());
    }

    @Test
    void truncatedCredentialsAreCorruptedAndTheBackupIsReported() throws IOException {
        Files.writeString(session.resolve("creds.json"), "{\"me\":{\"id\":");
        Files.writeString(session.resolve("creds.backup.json"), "{}");
        SessionStatus status = SessionInspector.inspect(session);
        assertEquals(State.CORRUPTED, status.state());
        assertTrue(status.backupAvailable());
    }
}
