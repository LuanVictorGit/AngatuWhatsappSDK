package com.angatusistemas.whatsapp.cli;

import com.angatusistemas.whatsapp.ConnectionState;
import com.angatusistemas.whatsapp.WhatsAppClient;
import com.angatusistemas.whatsapp.model.SessionStatus;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Command line over the SDK: pair a number (QR code or pairing code), inspect a session without
 * connecting, send a text and log out.
 *
 * @author Angatu Sistemas
 */
public final class Main {

    private static final Duration WAIT = Duration.ofSeconds(60);

    private Main() {
    }

    public static void main(String[] args) {
        // The QR code is drawn with block characters; the platform code page would turn them into '?'.
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        if (args.length == 0) {
            usage(System.err);
            System.exit(2);
        }
        CliArguments options;
        try {
            options = CliArguments.parse(Arrays.copyOfRange(args, 1, args.length));
        } catch (IllegalArgumentException e) {
            System.err.println("Erro nos argumentos: " + e.getMessage());
            System.exit(2);
            return;
        }
        try {
            switch (args[0]) {
                case "connect" -> connect(options);
                case "status" -> status(options);
                case "send" -> send(options);
                case "logout" -> logout(options);
                case "help", "--help", "-h" -> usage(System.out);
                default -> {
                    System.err.println("Comando desconhecido: " + args[0]);
                    usage(System.err);
                    System.exit(2);
                }
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Erro nos argumentos: " + e.getMessage());
            System.exit(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.exit(130);
        } catch (RuntimeException | ExecutionException | TimeoutException e) {
            System.err.println("Falha: " + e.getMessage());
            System.exit(1);
        }
        System.exit(0);
    }

    private static void connect(CliArguments options) throws InterruptedException, ExecutionException, TimeoutException {
        WhatsAppClient client = client(options);
        Runtime.getRuntime().addShutdownHook(new Thread(client::close, "angatu-cli-shutdown"));
        client.addConnectionStateListener((state, cause, detail) ->
                System.out.println("[estado] " + state + (cause != null ? " (" + cause + ")" : "")));
        client.addErrorListener(error -> System.out.println("[erro] " + error.getMessage()));
        client.addMessageListener(message -> System.out.println("[mensagem] " + message.senderId() + ": " + message.text()));
        client.addQrCodeListener(qr -> {
            System.out.println("\nEscaneie no celular: WhatsApp > Aparelhos conectados > Conectar um aparelho (ciclo " + qr.cycle() + ")");
            System.out.println(client.getQrCodeText());
        });
        if (options.optional("pairing-phone").isPresent()) {
            String code = client.requestPairingCode(options.require("pairing-phone")).get(60, TimeUnit.SECONDS);
            System.out.println("Código de pareamento: " + code.substring(0, 4) + "-" + code.substring(4)
                    + " (WhatsApp > Aparelhos conectados > Conectar com número de telefone)");
        } else {
            client.connect().get(60, TimeUnit.SECONDS);
        }
        System.out.println("Pressione Ctrl+C para encerrar.");
        new CountDownLatch(1).await();
    }

    private static void status(CliArguments options) {
        SessionStatus status = WhatsAppClient.inspectSession(Path.of(options.require("session-dir")));
        System.out.println("Sessão: " + status.state() + (status.phoneNumber() != null ? " (número " + status.phoneNumber() + ")" : ""));
        System.out.println(status.canResume()
                ? "Reconecta sem QR Code."
                : "Será preciso parear de novo" + (status.backupAvailable() ? ", ou restaurar pelo backup automático na conexão." : "."));
    }

    private static void send(CliArguments options) throws InterruptedException, ExecutionException, TimeoutException {
        try (WhatsAppClient client = client(options)) {
            awaitConnected(client);
            String id = client.sendText(options.require("to"), options.require("text")).get(5, TimeUnit.MINUTES);
            System.out.println("Mensagem enviada (id " + id + ").");
        }
    }

    private static void logout(CliArguments options) throws InterruptedException, ExecutionException, TimeoutException {
        try (WhatsAppClient client = client(options)) {
            awaitConnected(client);
            client.logout().get(60, TimeUnit.SECONDS);
            System.out.println("Sessão encerrada; será preciso parear de novo.");
        }
    }

    private static void awaitConnected(WhatsAppClient client) throws InterruptedException, ExecutionException, TimeoutException {
        BlockingQueue<ConnectionState> states = new ArrayBlockingQueue<>(64);
        client.addConnectionStateListener((state, cause, detail) -> states.offer(state));
        client.connect().get(60, TimeUnit.SECONDS);
        Set<ConnectionState> settled = EnumSet.of(ConnectionState.CONNECTED, ConnectionState.QR_PENDING, ConnectionState.FAILED,
                ConnectionState.LOGGED_OUT);
        long deadline = System.nanoTime() + WAIT.toNanos();
        ConnectionState reached = client.getConnectionState();
        while (!settled.contains(reached)) {
            ConnectionState next = states.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (next == null) {
                throw new IllegalStateException("a conexão não ficou pronta a tempo");
            }
            reached = next;
        }
        if (reached != ConnectionState.CONNECTED) {
            throw new IllegalStateException("a sessão não está pareada (estado " + reached + "); use o comando connect");
        }
    }

    private static WhatsAppClient client(CliArguments options) {
        return WhatsAppClient.builder()
                .instanceId(options.require("instance-id"))
                .sessionDirectory(options.require("session-dir"))
                .build();
    }

    private static void usage(PrintStream out) {
        out.println("""
                Uso: angatu-whatsapp-cli <comando> [opções]

                  connect --instance-id <id> --session-dir <pasta> [--pairing-phone <número com DDI>]
                      Conecta e mostra o QR Code (ou o código de pareamento) e as mensagens recebidas, até Ctrl+C.
                  status  --session-dir <pasta>
                      Lê a credencial salva, sem conectar, e diz se a sessão volta sem QR Code.
                  send    --instance-id <id> --session-dir <pasta> --to <número ou JID> --text <mensagem>
                      Envia um texto por uma sessão já pareada, passando pela fila com digitação.
                  logout  --instance-id <id> --session-dir <pasta>
                      Desconecta o aparelho da conta e apaga a credencial.

                Uma pasta de sessão por número: dois processos na mesma pasta derrubam um ao outro.
                """);
    }
}
