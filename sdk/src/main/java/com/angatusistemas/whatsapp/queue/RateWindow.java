package com.angatusistemas.whatsapp.queue;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Sliding one-minute window of timestamps, for the per-minute caps. Used only by the queue worker
 * thread, so it needs no synchronisation.
 *
 * @author Angatu Sistemas
 */
final class RateWindow {

    private static final long WINDOW_MS = 60_000;

    private final Deque<Long> stamps = new ArrayDeque<>();

    boolean allows(long now, int perMinute) {
        purge(now);
        return stamps.size() < perMinute;
    }

    void record(long now) {
        stamps.addLast(now);
    }

    long nextFreeAt(long now, int perMinute) {
        purge(now);
        if (stamps.size() < perMinute) {
            return now;
        }
        return stamps.peekFirst() + WINDOW_MS;
    }

    private void purge(long now) {
        while (!stamps.isEmpty() && now - stamps.peekFirst() >= WINDOW_MS) {
            stamps.pollFirst();
        }
    }
}
