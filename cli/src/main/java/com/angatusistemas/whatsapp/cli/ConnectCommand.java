package com.angatusistemas.whatsapp.cli;

import com.angatusistemas.whatsapp.ConnectionState;
import com.angatusistemas.whatsapp.WhatsAppClient;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Connects an instance, shows the QR Code (or requests a pairing code) if it
 * is not yet paired, then stays in the foreground printing incoming
 * messages and state changes until Ctrl+C. This is the command that
 * actually owns the bridge OS process for as long as it runs - closing it
 * ends the WhatsApp connection, matching this SDK's one-bridge-per-client
 * design.
 */
final class ConnectCommand {

    void run(String[] args) throws Exception {
        CliArgs cliArgs = CliArgs.parse(args);
        String instanceId = cliArgs.require("instance-id");
        Path sessionDirectory = Path.of(cliArgs.require("session-dir"));
        String displayName = cliArgs.optional("display-name", null);
        String pairingPhone = cliArgs.optional("pairing-phone", null);

        WhatsAppClient.Builder builder = WhatsAppClient.builder()
                .instanceId(instanceId)
                .sessionDirectory(sessionDirectory);
        if (displayName != null) {
            builder.displayName(displayName);
        }
        WhatsAppClient client = builder.build();
        Runtime.getRuntime().addShutdownHook(new Thread(client::close, "angatu-cli-shutdown"));

        ConnectionStateWaiter waiter = new ConnectionStateWaiter();
        waiter.attach(client);

        client.addConnectionStateListener((state, cause, detail) ->
                System.out.println("[estado] " + state + (cause != null ? " (causa=" + cause + ")" : "")));
        client.addErrorListener(error -> System.out.println("[erro] " + error.getMessage()));
        client.addMessageListener(message -> {
            if (!message.isFromMe()) {
                System.out.println("[mensagem] " + message.senderId() + ": " + message.text());
            }
        });
        client.addQrCodeListener(qr -> {
            System.out.println();
            System.out.println("Escaneie este QR Code no WhatsApp (Aparelhos conectados > Conectar um aparelho):");
            AsciiQrRenderer.print(qr, System.out);
        });

        System.out.println("conectando instancia '" + instanceId + "' (sessao em " + sessionDirectory.toAbsolutePath() + ")...");
        client.connect().join();

        if (pairingPhone != null) {
            String code = client.requestPairingCode(pairingPhone).get(30, TimeUnit.SECONDS);
            System.out.println("codigo de pareamento para " + pairingPhone + ": " + code);
        }

        ConnectionState reached = waiter.awaitState(Duration.ofSeconds(60),
                ConnectionState.CONNECTED, ConnectionState.QR_PENDING,
                ConnectionState.PAIRING_CODE_PENDING, ConnectionState.FAILED);

        if (reached == ConnectionState.FAILED) {
            System.err.println("falha ao conectar - veja as mensagens [erro] acima.");
            client.close();
            System.exit(1);
            return;
        }
        if (reached == ConnectionState.CONNECTED) {
            System.out.println("conectado!");
        } else {
            System.out.println("aguardando pareamento...");
        }
        System.out.println("pressione Ctrl+C para encerrar.");

        new CountDownLatch(1).await();
    }
}
