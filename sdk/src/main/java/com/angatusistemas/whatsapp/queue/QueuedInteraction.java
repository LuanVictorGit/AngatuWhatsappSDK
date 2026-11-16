package com.angatusistemas.whatsapp.queue;

import br.com.angatusistemas.lib.database.Saveable;
import lombok.Getter;
import lombok.Setter;

/**
 * One operation waiting its turn to reach WhatsApp, stored in SQLite instead of the heap: a
 * campaign of thousands of messages costs disk rows, not memory. The id is the time-ordered UUID
 * Saveable assigns, so equal priorities run in arrival order.
 *
 * @author Angatu Sistemas
 */
@Getter
@Setter
public final class QueuedInteraction extends Saveable {

    private String id;
    private String instanceId;
    private String action;
    private String kind;
    private String payload;
    private String chatId;
    private int priority;
    private long notBefore;
    private long createdAt;
    private int attempts;
    private String status;
    private int textLength;
    private long typingMs;
    private String typingPresence;
    private long timeoutMs;
    private String dedupeKey;
}
