package com.angatusistemas.whatsapp.cli;

import java.io.PrintStream;
import java.util.Arrays;

/**
 * Entry point for the optional {@code angatu-whatsapp-cli} tool: a thin
 * command-line front end over the public {@code WhatsAppClient} API, useful
 * for pairing a number or sending a one-off message without writing a Java
 * program. Every WhatsApp operation here goes through the same public SDK
 * the {@code sdk} module ships - this module adds no WhatsApp/Baileys
 * handling of its own.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage(System.err);
            System.exit(2);
        }

        String command = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);

        try {
            switch (command) {
                case "connect" -> new ConnectCommand().run(rest);
                case "status" -> new StatusCommand().run(rest);
                case "send" -> new SendCommand().run(rest);
                case "logout" -> new LogoutCommand().run(rest);
                case "help", "--help", "-h" -> printUsage(System.out);
                default -> {
                    System.err.println("comando desconhecido: " + command);
                    printUsage(System.err);
                    System.exit(2);
                }
            }
        } catch (CliArgumentException e) {
            System.err.println("erro nos argumentos: " + e.getMessage());
            printUsage(System.err);
            System.exit(2);
        } catch (Exception e) {
            System.err.println("falha: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void printUsage(PrintStream out) {
        out.println("""
                Uso: angatu-whatsapp-cli <comando> [opcoes]

                Comandos:
                  connect --instance-id <id> --session-dir <dir> [--display-name <nome>] [--pairing-phone <numero>]
                      Conecta a instancia, exibe o QR Code (ou codigo de pareamento, se
                      --pairing-phone for informado), e permanece em execucao imprimindo
                      mensagens recebidas ate Ctrl+C.

                  status --instance-id <id> --session-dir <dir>
                      Conecta brevemente a instancia (sessao ja pareada) e imprime o
                      estado atual da conexao.

                  send --instance-id <id> --session-dir <dir> --to <jid> --text <mensagem>
                      Conecta a uma instancia ja pareada, envia uma mensagem de texto e encerra.

                  logout --instance-id <id> --session-dir <dir>
                      Conecta a uma instancia ja pareada e invalida a sessao (sera
                      necessario um novo QR Code depois).

                Aviso: cada comando inicia seu proprio processo Bridge Node.js para o
                --session-dir informado. Nao execute dois comandos ao mesmo tempo com o
                mesmo --session-dir - eles disputariam o mesmo estado de autenticacao em
                disco.
                """);
    }
}
