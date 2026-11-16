package com.angatusistemas.whatsapp.queue;

/**
 * What an interaction does to the account, which decides its priority and the limits it obeys:
 * sends are spaced by the human gap and capped per minute, queries are capped per minute, and a
 * call rejection or a read receipt jumps ahead of a long broadcast.
 *
 * @author Angatu Sistemas
 */
public enum Kind {
    CALL(100),
    RECEIPT(50),
    PRESENCE(40),
    SEND(30),
    ACTION(30),
    QUERY(20),
    KEEPALIVE(10);

    private final int priority;

    Kind(int priority) {
        this.priority = priority;
    }

    public int priority() {
        return priority;
    }
}
