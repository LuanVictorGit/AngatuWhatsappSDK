package com.angatusistemas.whatsapp.listener;

import com.angatusistemas.whatsapp.model.PresenceUpdate;

/**
 * Receives online, typing and recording states of contacts in subscribed chats.
 *
 * @author Angatu Sistemas
 */
@FunctionalInterface
public interface PresenceListener {

    void onPresenceUpdated(PresenceUpdate update);
}
