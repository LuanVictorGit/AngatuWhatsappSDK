package com.angatusistemas.whatsapp.listener;

@FunctionalInterface
public interface MessageDeletedListener {
    void onMessageDeleted(String chatId, String messageId);
}
