package com.angatusistemas.whatsapp.listener;

import com.angatusistemas.whatsapp.model.Reaction;

/**
 * Receives emoji reactions added to or removed from messages.
 *
 * @author Angatu Sistemas
 */
@FunctionalInterface
public interface ReactionListener {

    void onReaction(Reaction reaction);
}
