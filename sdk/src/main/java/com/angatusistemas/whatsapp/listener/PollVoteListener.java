package com.angatusistemas.whatsapp.listener;

import com.angatusistemas.whatsapp.model.PollVote;

/**
 * Receives votes on polls this account sent, with the option names already decrypted.
 *
 * @author Angatu Sistemas
 */
@FunctionalInterface
public interface PollVoteListener {

    void onPollVote(PollVote vote);
}
