package com.angatusistemas.whatsapp.listener;

import com.fasterxml.jackson.databind.JsonNode;

@FunctionalInterface
public interface GroupUpdateListener {
    void onGroupUpdated(String groupJid, JsonNode updates);
}
