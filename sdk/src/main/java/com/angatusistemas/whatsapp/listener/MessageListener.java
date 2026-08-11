package com.angatusistemas.whatsapp.listener;

import com.angatusistemas.whatsapp.model.Message;

@FunctionalInterface
public interface MessageListener {
    void onMessageReceived(Message message);
}
