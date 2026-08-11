package com.angatusistemas.whatsapp.examples.simplebot;

import com.angatusistemas.whatsapp.WhatsAppClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * Minimal end-to-end usage of the SDK: connect one WhatsApp instance, save
 * the QR Code to a PNG file whenever pairing is needed, and echo back any
 * text message received. Run with:
 *
 * <pre>
 * mvn install                          # uma vez, para publicar o sdk no repositorio local
 * mvn -pl examples/simple-bot exec:java
 * </pre>
 *
 * or pass an instanceId and a session directory explicitly:
 * {@code exec:java -Dexec.args="my-bot ./my-bot-session"}.
 */
public final class SimpleBotExample {

    private static final Logger log = LoggerFactory.getLogger(SimpleBotExample.class);

    public static void main(String[] args) throws Exception {
        String instanceId = args.length > 0 ? args[0] : "simple-bot";
        Path sessionDirectory = Path.of(args.length > 1 ? args[1] : "./whatsapp-sessions/" + instanceId);

        WhatsAppClient client = WhatsAppClient.builder()
                .instanceId(instanceId)
                .sessionDirectory(sessionDirectory)
                .displayName("Angatu Simple Bot")
                .build();

        Runtime.getRuntime().addShutdownHook(new Thread(client::close, "shutdown-" + instanceId));

        client.addConnectionStateListener((state, cause, detail) ->
                log.info("estado da conexao: {} (causa={}, detalhe={})", state, cause, detail));

        client.addQrCodeListener(qr -> saveQrCode(client, sessionDirectory));

        client.addErrorListener(error -> log.error("erro reportado pelo SDK: {}", error.getMessage(), error));

        client.addMessageListener(message -> {
            if (message.isFromMe() || message.text() == null || message.text().isBlank()) {
                return;
            }
            log.info("mensagem recebida de {}: {}", message.senderId(), message.text());
            client.sendText(message.chatId(), "Echo: " + message.text())
                    .exceptionally(ex -> {
                        log.error("falha ao responder mensagem em {}: {}", message.chatId(), ex.getMessage(), ex);
                        return null;
                    });
        });

        log.info("conectando instancia '{}' (sessao em {})...", instanceId, sessionDirectory.toAbsolutePath());
        try {
            client.connect().join();
        } catch (RuntimeException e) {
            log.error("falha ao iniciar conexao: {}", e.getMessage(), e);
            System.exit(1);
            return;
        }

        log.info("bot em execucao - aguardando QR Code (se necessario) e mensagens. Ctrl+C para encerrar.");
        new CountDownLatch(1).await();
    }

    private static void saveQrCode(WhatsAppClient client, Path sessionDirectory) {
        byte[] png = client.getQrCodePng();
        if (png == null) {
            return;
        }
        try {
            Files.createDirectories(sessionDirectory);
            Path qrFile = sessionDirectory.resolve("qrcode.png");
            Files.write(qrFile, png);
            log.info("QR Code atualizado - escaneie o arquivo: {}", qrFile.toAbsolutePath());
        } catch (IOException e) {
            log.error("falha ao salvar QR Code em disco: {}", e.getMessage(), e);
        }
    }

    private SimpleBotExample() {
    }
}
