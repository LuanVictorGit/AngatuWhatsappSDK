package com.angatusistemas.whatsapp.cli;

import com.angatusistemas.whatsapp.ConnectionState;
import com.angatusistemas.whatsapp.WhatsAppClient;

import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Every CLI command needs to block its single foreground thread until the
 * client reaches a state worth acting on (connected, needs a QR/pairing
 * code, or failed) - this is that wait, factored out once instead of
 * repeated per command. Must be attached before {@link WhatsAppClient#connect()}
 * is called, so no transition is missed.
 */
final class ConnectionStateWaiter {

    private final BlockingQueue<ConnectionState> observedStates = new LinkedBlockingQueue<>();

    void attach(WhatsAppClient client) {
        client.addConnectionStateListener((state, cause, detail) -> observedStates.offer(state));
    }

    ConnectionState awaitState(Duration timeout, ConnectionState... acceptable) throws InterruptedException, TimeoutException {
        Set<ConnectionState> targets = EnumSet.copyOf(Arrays.asList(acceptable));
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (true) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new TimeoutException("tempo esgotado aguardando um dos estados " + targets);
            }
            ConnectionState observed = observedStates.poll(remainingNanos, TimeUnit.NANOSECONDS);
            if (observed != null && targets.contains(observed)) {
                return observed;
            }
        }
    }
}
