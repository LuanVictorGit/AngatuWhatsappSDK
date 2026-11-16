package com.angatusistemas.whatsapp.listener;

import com.angatusistemas.whatsapp.model.MessageUpdate;

/**
 * Receives delivery-status changes and edits of messages already in a chat.
 *
 * @author Angatu Sistemas
 */
@FunctionalInterface
public interface MessageUpdatedListener {

    void onMessageUpdated(MessageUpdate update);
}
