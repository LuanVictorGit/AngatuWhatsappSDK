package com.angatusistemas.whatsapp.listener;

import com.angatusistemas.whatsapp.ConnectionState;
import com.angatusistemas.whatsapp.DisconnectCause;

/**
 * Receives every connection state change of an instance; cause and detail are null when there is nothing to explain.
 *
 * @author Angatu Sistemas
 */
@FunctionalInterface
public interface ConnectionStateListener {

    void onConnectionStateChanged(ConnectionState state, DisconnectCause cause, String detail);
}
