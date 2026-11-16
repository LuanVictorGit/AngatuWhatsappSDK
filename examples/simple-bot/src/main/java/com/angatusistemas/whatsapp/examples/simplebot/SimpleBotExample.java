package com.angatusistemas.whatsapp.examples.simplebot;

import br.com.angatusistemas.lib.console.Console;
import com.angatusistemas.whatsapp.WhatsAppClient;
import com.angatusistemas.whatsapp.model.Button;
import com.angatusistemas.whatsapp.options.CallOptions;
import com.angatusistemas.whatsapp.options.HumanizationOptions;
import com.angatusistemas.whatsapp.options.ReadReceiptMode;
import com.angatusistemas.whatsapp.request.SendButtonsRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

/**
 * A small attendant bot: answers "oi" with a menu of buttons, reacts to the chosen button, declines
 * calls with a polite message, and reads messages the way a person does, right before replying.
 *
 * @author Angatu Sistemas
 */
public final class SimpleBotExample {

    private SimpleBotExample() {
    }

    public static void main(String[] args) throws InterruptedException {
        WhatsAppClient client = WhatsAppClient.builder()
                .instanceId("atendimento")
                .sessionDirectory(Path.of("whatsapp-sessions", "atendimento"))
                .humanization(HumanizationOptions.defaults()
                        .withTyping(Duration.ofSeconds(2), Duration.ofSeconds(6))
                        .withReadReceipts(ReadReceiptMode.ON_REPLY))
                .calls(CallOptions.rejectAll().withReply("Não atendemos ligações por aqui. Pode mandar uma mensagem que respondemos já!"))
                .build();
        Runtime.getRuntime().addShutdownHook(new Thread(client::close, "shutdown-bot"));

        client.addQrCodeListener(qr -> Console.info("Escaneie o QR Code:%n%s", client.getQrCodeText()));
        client.addConnectionStateListener((state, cause, detail) -> Console.info("Estado: %s %s", state, cause == null ? "" : cause));
        client.addMessageListener(message -> {
            if (message.isFromMe() || message.isGroup() || !message.hasText()) {
                return;
            }
            if (message.text().strip().equalsIgnoreCase("oi")) {
                client.sendButtons(SendButtonsRequest.of(message.chatId(), "Olá! Como podemos ajudar?",
                                Button.of("pedido", "Fazer um pedido"),
                                Button.of("status", "Status do pedido"),
                                Button.of("humano", "Falar com atendente"))
                        .withFooter("Angatu Sistemas"));
            }
        });
        client.addButtonListener(click -> {
            String reply = switch (click.buttonId()) {
                case "pedido" -> "Perfeito! Mande o nome do produto e a quantidade.";
                case "status" -> "Me informe o número do pedido, por favor.";
                default -> "Certo, já chamo alguém da equipe para falar com você.";
            };
            client.sendText(click.chatId(), reply);
        });

        if (client.sessionStatus().canResume()) {
            Console.info("Sessão salva encontrada: reconectando sem QR Code.");
        }
        client.connect().join();
        new CountDownLatch(1).await();
    }
}
