package com.angatusistemas.whatsapp.cli;

import com.angatusistemas.whatsapp.ConnectionState;
import com.angatusistemas.whatsapp.WhatsAppClient;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Connects to an already-paired instance, sends one text message, and exits. */
final class SendCommand {

    void run(String[] args) throws Exception {
        CliArgs cliArgs = CliArgs.parse(args);
        String instanceId = cliArgs.require("instance-id");
        Path sessionDirectory = Path.of(cliArgs.require("session-dir"));
        String to = cliArgs.require("to");
        String text = cliArgs.require("text");

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
                System.err.println("nao foi possivel enviar: instancia esta em estado " + reached
                        + " (esta sessao ja foi pareada? use 'connect' primeiro)");
                System.exit(1);
                return;
            }

            String messageId = client.sendText(to, text).get(30, TimeUnit.SECONDS);
            System.out.println("mensagem enviada, messageId=" + messageId);
        } finally {
            client.close();
        }
    }
}
