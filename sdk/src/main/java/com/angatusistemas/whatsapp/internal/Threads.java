package com.angatusistemas.whatsapp.internal;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * The few threads the SDK owns for the whole JVM: one daemon timer for heartbeats, keep-alives and
 * delays, shared by every client, and a virtual-thread executor for blocking work and for handing
 * results to callers, so caller code never runs on the WebSocket thread.
 *
 * @author Angatu Sistemas
 */
public final class Threads {

    public static final ScheduledExecutorService TIMER = newTimer();
    public static final ExecutorService VIRTUAL = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("angatu-whatsapp-", 0).factory());

    private Threads() {
    }

    private static ScheduledExecutorService newTimer() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "angatu-whatsapp-timer");
            thread.setDaemon(true);
            return thread;
        });
        // Cancelled keep-alives and timeouts leave the queue at once instead of waiting for their delay.
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }
}
