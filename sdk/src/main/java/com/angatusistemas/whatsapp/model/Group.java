package com.angatusistemas.whatsapp.model;

import java.time.Instant;
import java.util.List;

/**
 * Metadata of a group: subject, description, owner and members.
 *
 * @author Angatu Sistemas
 */
public record Group(
        String id,
        String subject,
        String description,
        String owner,
        Instant createdAt,
        boolean announceOnly,
        boolean restricted,
        List<GroupParticipant> participants) {

    public Group {
        participants = participants == null ? List.of() : List.copyOf(participants);
    }
}
