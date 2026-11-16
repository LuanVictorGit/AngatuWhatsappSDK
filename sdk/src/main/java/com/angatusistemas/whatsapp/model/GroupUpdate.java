package com.angatusistemas.whatsapp.model;

import java.util.List;

/**
 * A change in a group: members added, removed, promoted or demoted (action add, remove, promote,
 * demote), or the subject or description edited (action update).
 *
 * @author Angatu Sistemas
 */
public record GroupUpdate(String groupJid, String action, List<String> participants, String subject, String description) {

    public GroupUpdate {
        participants = participants == null ? List.of() : List.copyOf(participants);
    }
}
