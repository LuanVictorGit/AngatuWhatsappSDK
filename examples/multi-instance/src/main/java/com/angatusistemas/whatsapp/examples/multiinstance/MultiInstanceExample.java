package com.angatusistemas.whatsapp.examples.multiinstance;

import br.com.angatusistemas.lib.console.Console;
import com.angatusistemas.whatsapp.WhatsAppClient;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Several numbers in one application: each client has its own session folder and queue, and all of
 * them share one bridge process, so a second number costs a queue and a socket, not a second Node.js.
 *
 * @author Angatu Sistemas
 */
public final class MultiInstanceExample {

    private MultiInstanceExample() {
    }

    public static void main(String[] args) throws InterruptedException {
        List<String> ids = args.length > 0 ? List.of(args) : List.of("loja-centro", "loja-norte");
        List<WhatsAppClient> clients = new ArrayList<>();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> clients.forEach(WhatsAppClient::close), "shutdown-clients"));

        for (String id : ids) {
            WhatsAppClient client = WhatsAppClient.builder()
                    .instanceId(id)
                    .sessionDirectory(Path.of("whatsapp-sessions", id))
                    .build();
            client.addConnectionStateListener((state, cause, detail) -> Console.info("[%s] %s %s", id, state, cause == null ? "" : cause));
            client.addQrCodeListener(qr -> Console.info("[%s] QR Code pronto (ciclo %s):%n%s", id, qr.cycle(), client.getQrCodeText()));
            client.addMessageListener(message -> Console.info("[%s] %s: %s", id, message.senderId(), message.text()));
            client.addErrorListener(error -> Console.warn("[%s] %s", id, error.getMessage()));
            clients.add(client);
            Console.info("[%s] sessão: %s", id, client.sessionStatus().state());
            client.connect().exceptionally(error -> {
                Console.error("[" + id + "] não conectou: " + error.getMessage());
                return null;
            });
        }
        new CountDownLatch(1).await();
    }
}
