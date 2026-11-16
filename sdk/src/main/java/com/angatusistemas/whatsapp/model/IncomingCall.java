package com.angatusistemas.whatsapp.model;

import java.time.Instant;

/**
 * A voice or video call ringing on the account right now.
 *
 * @author Angatu Sistemas
 */
public record IncomingCall(String callId, String from, String chatId, boolean isVideo, boolean isGroup, Instant timestamp) {
}
