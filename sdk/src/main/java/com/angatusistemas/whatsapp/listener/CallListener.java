package com.angatusistemas.whatsapp.listener;

import com.angatusistemas.whatsapp.model.IncomingCall;

/**
 * Receives calls ringing on the account, before any automatic rejection runs.
 *
 * @author Angatu Sistemas
 */
@FunctionalInterface
public interface CallListener {

    void onCall(IncomingCall call);
}
