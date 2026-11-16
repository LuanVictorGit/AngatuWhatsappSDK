package com.angatusistemas.whatsapp.queue;

import br.com.angatusistemas.lib.database.Saveable;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The SQL behind the queue, one indexed query per decision: the next due interaction, the earliest
 * future one, and the rows of one instance. Nothing is cached; every read comes from SQLite.
 *
 * @author Angatu Sistemas
 */
public final class InteractionStore {

    static final String PENDING = "PENDING";
    static final String RUNNING = "RUNNING";

    private static final AtomicBoolean INDEXED = new AtomicBoolean();
    private static final String TABLE = Saveable.tableName(QueuedInteraction.class);
    private static final String BASE = "SELECT data FROM " + TABLE
            + " WHERE json_extract(data,'$.instanceId') = ? AND json_extract(data,'$.status') = ?";
    private static final String ORDER = " ORDER BY json_extract(data,'$.priority') DESC, json_extract(data,'$.notBefore'), id LIMIT 1";
    private static final String DUE = " AND json_extract(data,'$.notBefore') <= ?";
    private static final String NO_SENDS = " AND json_extract(data,'$.kind') <> 'SEND'";
    private static final String NO_QUERIES = " AND json_extract(data,'$.kind') <> 'QUERY'";

    private InteractionStore() {
    }

    static void ensureIndexes() {
        if (INDEXED.compareAndSet(false, true)) {
            Saveable.createIndex(QueuedInteraction.class, "instanceId", "status");
            Saveable.createIndex(ButtonMenu.class, "instanceId", "chatId");
        }
    }

    static Optional<QueuedInteraction> nextDue(String instanceId, long now, boolean sendsAllowed, boolean queriesAllowed) {
        String sql = BASE + DUE + (sendsAllowed ? "" : NO_SENDS) + (queriesAllowed ? "" : NO_QUERIES) + ORDER;
        return Saveable.query(QueuedInteraction.class, sql, instanceId, PENDING, now).stream().findFirst();
    }

    static Optional<QueuedInteraction> earliestAfter(String instanceId, long now) {
        String sql = BASE + " AND json_extract(data,'$.notBefore') > ? ORDER BY json_extract(data,'$.notBefore') LIMIT 1";
        return Saveable.query(QueuedInteraction.class, sql, instanceId, PENDING, now).stream().findFirst();
    }

    static boolean hasDue(String instanceId, long now, String kind) {
        String sql = BASE + DUE + " AND json_extract(data,'$.kind') = ? LIMIT 1";
        return !Saveable.query(QueuedInteraction.class, sql, instanceId, PENDING, now, kind).isEmpty();
    }

    static List<QueuedInteraction> withStatus(String instanceId, String status) {
        return Saveable.query(QueuedInteraction.class, BASE + " ORDER BY id LIMIT 1000", instanceId, status);
    }

    static List<QueuedInteraction> pendingWithKey(String instanceId, String dedupeKey) {
        return Saveable.query(QueuedInteraction.class, BASE + " AND json_extract(data,'$.dedupeKey') = ?", instanceId, PENDING, dedupeKey);
    }

    static QueuedInteraction find(String id) {
        return Saveable.findById(QueuedInteraction.class, id);
    }
}
