package com.angatusistemas.whatsapp.examples.multiinstance;

import com.angatusistemas.whatsapp.WhatsAppClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Demonstrates running several independent WhatsApp connections in one JVM:
 * each {@link WhatsAppClient} gets its own instanceId and sessionDirectory,
 * so each is a fully separate WhatsApp number/login with its own bridge
 * process - a crash or disconnect on one never affects the others. Run
 * with the default two instances, or pass your own instance ids:
 *
 * <pre>
 * mvn install                                                  # uma vez, para publicar o sdk no repositorio local
 * mvn -pl examples/multi-instance exec:java -Dexec.args="sales support"
 * </pre>
 */
public final class MultiInstanceExample {

    private static final Logger log = LoggerFactory.getLogger(MultiInstanceExample.class);

    public static void main(String[] args) throws InterruptedException {
        List<String> instanceIds = args.length > 0 ? List.of(args) : List.of("instance-a", "instance-b");
        List<WhatsAppClient> clients = new ArrayList<>();

        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> clients.forEach(WhatsAppClient::close), "shutdown-all-instances"));

        for (String instanceId : instanceIds) {
            Path sessionDirectory = Path.of("./whatsapp-sessions/" + instanceId);
            WhatsAppClient client = WhatsAppClient.builder()
                    .instanceId(instanceId)
                    .sessionDirectory(sessionDirectory)
                    .build();
            clients.add(client);
            attachLogging(client, instanceId);
        }

        for (WhatsAppClient client : clients) {
            log.info("conectando instancia '{}'...", client.getInstanceId());
            client.connect().exceptionally(ex -> {
                log.error("falha ao conectar '{}': {}", client.getInstanceId(), ex.getMessage(), ex);
                return null;
            });
        }

        log.info("{} instancia(s) em inicializacao - Ctrl+C para encerrar todas.", clients.size());
        new CountDownLatch(1).await();
    }

    private static void attachLogging(WhatsAppClient client, String instanceId) {
        client.addConnectionStateListener((state, cause, detail) ->
                log.info("[{}] estado: {} (causa={}, detalhe={})", instanceId, state, cause, detail));
        client.addQrCodeListener(qr ->
                log.info("[{}] QR Code disponivel - use client.getQrCodePng() para exibi-lo", instanceId));
        client.addMessageListener(message ->
                log.info("[{}] mensagem de {}: {}", instanceId, message.senderId(), message.text()));
        client.addErrorListener(error -> log.error("[{}] erro: {}", instanceId, error.getMessage(), error));
    }

    private MultiInstanceExample() {
    }
}
