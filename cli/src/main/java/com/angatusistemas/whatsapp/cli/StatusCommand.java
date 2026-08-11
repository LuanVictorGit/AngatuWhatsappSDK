package com.angatusistemas.whatsapp.cli;

import com.angatusistemas.whatsapp.ConnectionState;
import com.angatusistemas.whatsapp.WhatsAppClient;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Briefly connects to an already-paired instance (resuming its persisted
 * session, no new QR expected) just to read its current state, then
 * disconnects. There is no long-running daemon this could otherwise query -
 * see {@code Main}'s usage text for why this must not run concurrently with
 * another command against the same {@code --session-dir}.
 */
final class StatusCommand {

    void run(String[] args) throws Exception {
        CliArgs cliArgs = CliArgs.parse(args);
        String instanceId = cliArgs.require("instance-id");
        Path sessionDirectory = Path.of(cliArgs.require("session-dir"));

        WhatsAppClient client = WhatsAppClient.builder()
                .instanceId(instanceId)
                .sessionDirectory(sessionDirectory)
                .build();
        try {
            ConnectionStateWaiter waiter = new ConnectionStateWaiter();
            waiter.attach(client);

            client.connect().join();
            ConnectionState reached = waiter.awaitState(Duration.ofSeconds(45),
                    ConnectionState.CONNECTED, ConnectionState.QR_PENDING, ConnectionState.PAIRING_CODE_PENDING,
                    ConnectionState.FAILED, ConnectionState.LOGGED_OUT);

            System.out.println("instanceId: " + instanceId);
            System.out.println("estado: " + reached);
            if (reached == ConnectionState.QR_PENDING || reached == ConnectionState.PAIRING_CODE_PENDING) {
                System.out.println("(sessao ainda nao pareada - use 'connect' para escanear o QR Code)");
            }
        } finally {
            client.close();
        }
    }
}
