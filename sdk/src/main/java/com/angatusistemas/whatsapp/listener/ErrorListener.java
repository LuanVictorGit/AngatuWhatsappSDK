package com.angatusistemas.whatsapp.listener;

import com.angatusistemas.whatsapp.exception.WhatsAppException;

@FunctionalInterface
public interface ErrorListener {
    void onError(WhatsAppException error);
}
