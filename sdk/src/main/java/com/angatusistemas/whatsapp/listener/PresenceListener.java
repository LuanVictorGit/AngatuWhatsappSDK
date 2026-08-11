package com.angatusistemas.whatsapp.listener;

import com.fasterxml.jackson.databind.JsonNode;

@FunctionalInterface
public interface PresenceListener {
    void onPresenceUpdated(String chatId, JsonNode presences);
}
