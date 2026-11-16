package com.angatusistemas.whatsapp.internal;

import br.com.angatusistemas.lib.console.Console;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bounded, ordered hand-off of events from the bridge connection to one client's listeners, run on
 * one virtual thread. Bounded on purpose: an unbounded queue in front of a slow listener was a
 * heap that only grew. When full, droppable events (presence, delivery status) are skipped and
 * the others make the producer wait, which pushes back to the bridge, which skips its own
 * droppable events.
 *
 * @author Angatu Sistemas
 */
public final class EventInbox implements AutoCloseable {

    private static final int CAPACITY = 1_024;

    private final BlockingQueue<Runnable> tasks = new ArrayBlockingQueue<>(CAPACITY);
    private final LongAdder dropped = new LongAdder();
    private final String name;
    private final Thread consumer;
    private volatile boolean closed;

    public EventInbox(String name) {
        this.name = name;
        this.consumer = Thread.ofVirtual().name("angatu-whatsapp-events-" + name).start(this::drain);
    }

    public void submit(Runnable task, boolean droppable) {
        if (closed) {
            return;
        }
        if (droppable) {
            if (!tasks.offer(task)) {
                dropped.increment();
                if (dropped.sum() % 1_000 == 1) {
                    Console.warn("[whatsapp] %s: listeners are slow, %s presence/status events skipped so far", name, dropped.sum());
                }
            }
            return;
        }
        try {
            tasks.put(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        closed = true;
        consumer.interrupt();
        tasks.clear();
    }

    private void drain() {
        while (!closed) {
            Runnable task;
            try {
                task = tasks.take();
            } catch (InterruptedException e) {
                return;
            }
            try {
                task.run();
            } catch (RuntimeException e) {
                Console.error("[whatsapp] " + name + ": a listener threw " + e, e);
            }
        }
    }
}
