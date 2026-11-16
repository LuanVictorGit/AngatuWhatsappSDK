package com.angatusistemas.whatsapp.model;

import java.time.Instant;
import java.util.List;

/**
 * A vote on a poll this account sent, decrypted and with the chosen option names; an empty list
 * means the voter removed their vote.
 *
 * @author Angatu Sistemas
 */
public record PollVote(String chatId, String pollMessageId, String voterId, List<String> selectedOptions, Instant timestamp) {

    public PollVote {
        selectedOptions = selectedOptions == null ? List.of() : List.copyOf(selectedOptions);
    }
}
