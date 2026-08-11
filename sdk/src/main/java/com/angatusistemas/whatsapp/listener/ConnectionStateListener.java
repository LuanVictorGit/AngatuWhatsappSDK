package com.angatusistemas.whatsapp.listener;

import com.angatusistemas.whatsapp.ConnectionState;
import com.angatusistemas.whatsapp.DisconnectCause;

@FunctionalInterface
public interface ConnectionStateListener {
    /** {@code cause}/{@code detail} are null for transitions that are not the result of a disconnect (e.g. reaching CONNECTED). */
    void onConnectionStateChanged(ConnectionState state, DisconnectCause cause, String detail);
}
