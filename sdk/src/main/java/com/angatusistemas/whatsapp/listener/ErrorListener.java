package com.angatusistemas.whatsapp.listener;

import com.angatusistemas.whatsapp.exception.WhatsAppException;

/**
 * Receives failures that happen away from any caller: reconnection attempts and background interactions.
 *
 * @author Angatu Sistemas
 */
@FunctionalInterface
public interface ErrorListener {

    void onError(WhatsAppException error);
}
