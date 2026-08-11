package com.angatusistemas.whatsapp.listener;

@FunctionalInterface
public interface ReactionListener {
    /** {@code emoji} is null and {@code removed} is true when a previously sent reaction was cleared. */
    void onReactionReceived(String chatId, String messageId, String senderId, String emoji, boolean removed);
}
