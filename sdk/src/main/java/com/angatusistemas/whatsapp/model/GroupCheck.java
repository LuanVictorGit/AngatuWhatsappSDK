package com.angatusistemas.whatsapp.model;

/**
 * Whether a group exists and whether this account is a member of it; subject is null when the
 * account cannot see the group.
 *
 * @author Angatu Sistemas
 */
public record GroupCheck(String groupJid, boolean exists, boolean isMember, String subject) {
}
