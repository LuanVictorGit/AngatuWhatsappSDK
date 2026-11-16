package com.angatusistemas.whatsapp.listener;

import com.angatusistemas.whatsapp.model.Message;

/**
 * Receives every new message that reaches the account, including those the owner sends from the phone.
 *
 * @author Angatu Sistemas
 */
@FunctionalInterface
public interface MessageListener {

    void onMessageReceived(Message message);
}
