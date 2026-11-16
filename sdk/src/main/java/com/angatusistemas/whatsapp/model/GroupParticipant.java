package com.angatusistemas.whatsapp.model;

/**
 * A member of a group and its role.
 *
 * @author Angatu Sistemas
 */
public record GroupParticipant(String id, boolean isAdmin, boolean isSuperAdmin) {
}
