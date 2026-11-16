package com.angatusistemas.whatsapp.listener;

/**
 * Receives messages revoked for everyone.
 *
 * @author Angatu Sistemas
 */
@FunctionalInterface
public interface MessageDeletedListener {

    void onMessageDeleted(String chatId, String messageId);
}
