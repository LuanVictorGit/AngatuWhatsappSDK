package com.angatusistemas.whatsapp.listener;

import com.fasterxml.jackson.databind.JsonNode;

/** {@code updates} carries whatever partial fields Baileys reported changed - there is no stable abstract shape for it. */
@FunctionalInterface
public interface MessageUpdatedListener {
    void onMessageUpdated(String chatId, String messageId, JsonNode updates);
}
