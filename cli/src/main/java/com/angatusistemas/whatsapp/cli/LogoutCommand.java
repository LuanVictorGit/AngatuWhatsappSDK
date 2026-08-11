package com.angatusistemas.whatsapp.cli;

import com.angatusistemas.whatsapp.ConnectionState;
import com.angatusistemas.whatsapp.WhatsAppClient;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Connects to an already-paired instance and invalidates its session; a new QR Code will be required afterward. */
final class LogoutCommand {

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
                    ConnectionState.CONNECTED, ConnectionState.QR_PENDING,
                    ConnectionState.PAIRING_CODE_PENDING, ConnectionState.FAILED);

            if (reached != ConnectionState.CONNECTED) {
                System.err.println("nada a fazer logout: instancia esta em estado " + reached);
                System.exit(1);
                return;
            }

            client.logout().get(30, TimeUnit.SECONDS);
            System.out.println("logout solicitado com sucesso.");
        } finally {
            client.close();
        }
    }
}
