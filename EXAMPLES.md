# Exemplos de uso — Angatu WhatsApp SDK

Referência exaustiva de todo método público do SDK, com um exemplo funcional para cada um.
Este documento existe para que qualquer pessoa — ou qualquer assistente de IA gerando código
Java contra esta biblioteca — encontre o uso correto de qualquer parte da API sem precisar
adivinhar nomes de método, tipos de retorno ou parâmetros obrigatórios.

Todo trecho aqui usa nomes de classe/método reais, copiados diretamente do código-fonte em
`sdk/src/main/java/com/angatusistemas/whatsapp/` — nada foi aproximado ou simplificado a ponto
de deixar de compilar. Para conceitos e arquitetura, veja o [README](README.md); este arquivo é
propositalmente só código.

## Sumário

- [Configuração da dependência](#configuração-da-dependência)
- [1. Ciclo de vida do WhatsAppClient](#1-ciclo-de-vida-do-whatsappclient)
- [2. Consultando estado](#2-consultando-estado)
- [3. Todos os listeners de evento](#3-todos-os-listeners-de-evento)
- [4. Enviando mensagens](#4-enviando-mensagens)
- [5. Baixando mídia](#5-baixando-mídia)
- [6. Contatos](#6-contatos)
- [7. Grupos](#7-grupos)
- [8. Múltiplas instâncias](#8-múltiplas-instâncias)
- [9. Tratamento de erros](#9-tratamento-de-erros)
- [10. Modelos de dados (referência de campos)](#10-modelos-de-dados-referência-de-campos)
- [11. Exemplo completo: bot com comandos](#11-exemplo-completo-bot-com-comandos)
- [12. CLI: exemplos de linha de comando](#12-cli-exemplos-de-linha-de-comando)
- [13. Referência rápida de métodos](#13-referência-rápida-de-métodos)

## Configuração da dependência

```xml
<dependency>
    <groupId>com.angatusistemas</groupId>
    <artifactId>angatu-whatsapp</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Todo exemplo abaixo assume estes imports (omitidos nos trechos individuais por brevidade):

```java
import com.angatusistemas.whatsapp.WhatsAppClient;
import com.angatusistemas.whatsapp.ConnectionState;
import com.angatusistemas.whatsapp.DisconnectCause;
import com.angatusistemas.whatsapp.model.*;
import com.angatusistemas.whatsapp.request.*;
import com.angatusistemas.whatsapp.exception.*;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
```

## 1. Ciclo de vida do WhatsAppClient

### Criando um cliente (todas as opções do builder)

```java
WhatsAppClient client = WhatsAppClient.builder()
        .instanceId("vendas")                       // obrigatorio - unico nesta JVM
        .sessionDirectory("./sessions/vendas")        // obrigatorio - unico nesta JVM (String ou Path)
        .displayName("Vendas - Angatu")               // opcional - nome em "Aparelhos conectados"
        .markOnlineOnConnect(true)                    // opcional - default false
        .minReconnectDelay(Duration.ofSeconds(2))     // opcional - default 1s
        .maxReconnectDelay(Duration.ofSeconds(60))    // opcional - default 30s
        .maxReconnectAttempts(15)                     // opcional - default 10
        .nodeVersion("24.19.0")                        // opcional - default: versao LTS pinada pelo SDK
        .bridgeHost("127.0.0.1")                       // opcional/avancado - default 127.0.0.1
        .bridgePort(0)                                 // opcional/avancado - default: porta efemera do SO
        .build();
```

`sessionDirectory` também aceita `java.nio.file.Path` diretamente:

```java
WhatsAppClient client = WhatsAppClient.builder()
        .instanceId("vendas")
        .sessionDirectory(Path.of("sessions", "vendas"))
        .build();
```

`instanceId`/`sessionDirectory` duplicados (nesta JVM) lançam `InstanceException` imediatamente,
antes de qualquer processo bridge ser iniciado:

```java
try {
    WhatsAppClient duplicado = WhatsAppClient.builder()
            .instanceId("vendas")                 // mesmo id de um cliente ja construido
            .sessionDirectory("./outro-dir")
            .build();
} catch (InstanceException e) {
    System.err.println("conflito: " + e.getMessage());
}
```

### connect()

```java
CompletableFuture<Void> connected = client.connect();
connected.join(); // ou .get(timeout, unit) - completa quando o BRIDGE aceita iniciar a conexao,
                   // nao quando o WhatsApp termina de autenticar (isso vem pelos listeners)
```

```java
// Forma assíncrona, sem bloquear a thread chamadora
client.connect().exceptionally(ex -> {
    log.error("falha ao iniciar conexao: {}", ex.getMessage(), ex);
    return null;
});
```

### disconnect()

```java
// Fecha a conexao com o WhatsApp mas mantem o processo bridge e a sessao no disco -
// chamar connect() novamente depois retoma sem novo QR Code.
client.disconnect().join();
```

### logout()

```java
// Invalida a sessao no WhatsApp - depois disso, um novo QR Code sera necessario.
// Requer que o cliente esteja conectado (lanca ConnectionException com NOT_CONNECTED caso contrario).
client.logout().join();
```

### requestPairingCode(String)

```java
client.connect().join();
String codigo = client.requestPairingCode("5511999999999").get(30, TimeUnit.SECONDS);
System.out.println("Digite no WhatsApp: " + codigo);
```

### close()

```java
// Sincrono, idempotente: pede shutdown educado ao bridge, espera confirmacao, forca termino
// se necessario, libera instanceId/sessionDirectory. Chame ao encerrar a aplicacao.
client.close();
```

```java
// Padrao recomendado: registrar ANTES de connect(), assim close() roda mesmo se connect() falhar
// ou se o processo receber SIGTERM/Ctrl+C.
Runtime.getRuntime().addShutdownHook(new Thread(client::close));
client.connect().join();
```

## 2. Consultando estado

```java
String id = client.getInstanceId();                 // "vendas"

ConnectionState estado = client.getConnectionState();
// DISCONNECTED, CONNECTING, QR_PENDING, PAIRING_CODE_PENDING, CONNECTED, RECONNECTING,
// LOGGED_OUT, FAILED

String qrAtual = client.getCurrentQrCode();          // conteudo bruto do QR, ou null se nao ha QR pendente

byte[] qrPng = client.getQrCodePng();                // PNG 300x300, ou null se nao ha QR pendente
byte[] qrPngGrande = client.getQrCodePng(600);        // PNG no tamanho pedido
```

## 3. Todos os listeners de evento

Cada `addXListener` aceita múltiplos registros (todos são chamados, na ordem de registro) e
nunca lança para quem chamou — exceções dentro do seu listener são capturadas e logadas
internamente pelo SDK, nunca derrubam a aplicação.

```java
// MessageListener - toda mensagem nova (própria ou de terceiros)
client.addMessageListener(message -> {
    System.out.println(message.senderId() + " em " + message.chatId() + ": " + message.text());
});

// MessageUpdatedListener - edição/atualização de uma mensagem existente
client.addMessageUpdatedListener((chatId, messageId, updates) -> {
    System.out.println("mensagem " + messageId + " em " + chatId + " atualizada: " + updates);
});

// MessageDeletedListener - mensagem apagada
client.addMessageDeletedListener((chatId, messageId) -> {
    System.out.println("mensagem " + messageId + " apagada em " + chatId);
});

// ReactionListener - reação adicionada ou removida (removed=true e emoji=null quando removida)
client.addReactionListener((chatId, messageId, senderId, emoji, removed) -> {
    if (removed) {
        System.out.println(senderId + " removeu a reacao de " + messageId);
    } else {
        System.out.println(senderId + " reagiu com " + emoji + " em " + messageId);
    }
});

// ConnectionStateListener - toda mudança de estado da conexão
client.addConnectionStateListener((state, cause, detail) -> {
    System.out.println("estado: " + state + " causa: " + cause + " detalhe: " + detail);
    if (state == ConnectionState.FAILED) {
        // maxReconnectAttempts esgotado - o SDK parou de tentar sozinho
    }
});

// QrCodeListener - novo QR Code disponível (chamado de novo a cada rotação do QR)
client.addQrCodeListener(qrCode -> {
    byte[] png = client.getQrCodePng();
    // salvar em disco, servir por HTTP, exibir no terminal, etc.
});

// GroupUpdateListener - metadados de um grupo mudaram (assunto, participantes, ...)
client.addGroupUpdateListener((groupJid, updates) -> {
    System.out.println("grupo " + groupJid + " atualizado: " + updates);
});

// PresenceListener - presença (online/digitando/gravando) mudou num chat
client.addPresenceListener((chatId, presences) -> {
    System.out.println("presenca em " + chatId + ": " + presences);
});

// ErrorListener - erros assíncronos que não são resposta direta de uma chamada
client.addErrorListener(error -> {
    System.err.println("erro do SDK [" + error.errorCode().orElse(null) + "]: " + error.getMessage());
});
```

## 4. Enviando mensagens

Todo método de envio retorna `CompletableFuture<String>` com o `messageId` gerado pelo WhatsApp
(exceto `sendReaction`, que retorna `CompletableFuture<Void>`). Nenhum bloqueia a thread chamadora.

### Texto

```java
// Simples
client.sendText("5511999999999@s.whatsapp.net", "Ola!");

// Respondendo a outra mensagem (quotedMessageId opcional)
client.sendText("5511999999999@s.whatsapp.net", "Respondendo...", "3EB0ABCDEF123456");

// Tratando o resultado
client.sendText(chatId, "Ola!").thenAccept(messageId ->
        System.out.println("enviado, id=" + messageId));
```

### Imagem

```java
client.sendImage(SendImageRequest.builder()
        .to(chatId)                          // obrigatorio
        .filePath("/caminho/para/foto.jpg")   // obrigatorio - lido pelo processo bridge local
        .caption("Legenda opcional")           // opcional
        .quotedMessageId(quotedId)             // opcional
        .build());
```

### Vídeo

```java
client.sendVideo(SendVideoRequest.builder()
        .to(chatId)
        .filePath("/caminho/video.mp4")
        .caption("Legenda opcional")
        .gifPlayback(false)                    // opcional - default false; true = enviar como GIF
        .quotedMessageId(quotedId)
        .build());
```

### Áudio

```java
client.sendAudio(SendAudioRequest.builder()
        .to(chatId)
        .filePath("/caminho/audio.ogg")
        .ptt(true)                             // opcional - default false; true = nota de voz
        .quotedMessageId(quotedId)
        .build());
```

### Documento

```java
client.sendDocument(SendDocumentRequest.builder()
        .to(chatId)
        .filePath("/caminho/relatorio.pdf")
        .fileName("relatorio-mensal.pdf")       // obrigatorio - nome exibido ao destinatario
        .mimetype("application/pdf")            // obrigatorio
        .caption("Segue o relatorio")            // opcional
        .quotedMessageId(quotedId)
        .build());
```

### Figurinha (sticker)

```java
client.sendSticker(SendStickerRequest.builder()
        .to(chatId)
        .filePath("/caminho/figurinha.webp")     // obrigatorio - formato WebP
        .quotedMessageId(quotedId)
        .build());
```

### Localização

```java
// Coordenadas sao obrigatorias no proprio builder(lat, lon), nao em setters -
// double primitivo nao consegue representar "nao informado" como null consegue.
client.sendLocation(SendLocationRequest.builder(-23.5505, -46.6333)
        .to(chatId)
        .name("Sede Angatu")                    // opcional
        .address("Av. Paulista, Sao Paulo - SP") // opcional
        .quotedMessageId(quotedId)
        .build());
```

### Cartão de contato

```java
client.sendContact(SendContactRequest.builder()
        .to(chatId)
        .displayName("Suporte Angatu")          // obrigatorio
        .phoneNumber("5511988888888")            // obrigatorio
        .organization("Angatu Sistemas")          // opcional
        .quotedMessageId(quotedId)
        .build());
```

### Reação

```java
// Adicionar uma reacao
client.sendReaction(SendReactionRequest.builder()
        .chatId(chatId)
        .messageId(messageId)
        .emoji("👍")                 // "👍"
        .fromMe(false)                          // true se a mensagem reagida foi enviada por voce
        .participant(participantJid)             // obrigatorio apenas em grupos - JID de quem enviou
        .build());

// Remover uma reacao existente: omita emoji() (fica null)
client.sendReaction(SendReactionRequest.builder()
        .chatId(chatId)
        .messageId(messageId)
        .fromMe(false)
        .build());
```

### Editar uma mensagem já enviada

```java
client.editMessage(EditMessageRequest.builder()
        .chatId(chatId)
        .messageId(messageId)                    // a mensagem a corrigir
        .text("Texto corrigido")                 // obrigatorio, nao pode ser vazio
        .fromMe(true)                            // padrao: true
        .build());
```

> **Janela de tempo.** O WhatsApp só aceita a edição nos primeiros minutos
> depois da entrega — cerca de 15, decididos pelo servidor e **sem limite
> publicado**. Não há como perguntar antes se uma mensagem ainda está no prazo:
> passado ele, o servidor recusa e o `CompletableFuture` completa
> excepcionalmente. Trate a recusa como resposta, não como erro inesperado.

### Apagar para todos

```java
client.deleteMessage(DeleteMessageRequest.builder()
        .chatId(chatId)
        .messageId(messageId)
        .fromMe(true)                            // false + participant() para apagar de outro membro (admin de grupo)
        .build());
```

Apagar só do seu lado não é oferecido: isso é uma decisão sobre o que um
histórico local mostra, e pertence a quem mantém esse histórico — não ao
WhatsApp.

## 5. Baixando mídia

```java
client.downloadMedia(chatId, messageId).thenAccept(media -> {
    byte[] bytes = media.data();
    String mimetype = media.mimetype();       // ex.: "image/jpeg"
    try {
        java.nio.file.Files.write(Path.of("recebido" + extensaoPara(mimetype)), bytes);
    } catch (java.io.IOException e) {
        // trate a falha de escrita em disco
    }
});
```

`downloadMedia` só funciona para mensagens de mídia observadas durante a vida do processo atual
(o bridge mantém um cache limitado por tempo/tamanho, não um histórico completo — veja o README
"Limitações conhecidas").

## 6. Contatos

```java
client.getContact("5511999999999@s.whatsapp.net").thenAccept(contact -> {
    System.out.println(contact.name() + " - " + contact.status());
    System.out.println("tem WhatsApp: " + contact.onWhatsApp());
    System.out.println("foto: " + contact.profilePictureUrl());   // pode ser null
});
```

## 7. Grupos

### Metadados de um grupo

```java
client.getGroupMetadata(groupJid).thenAccept(group -> {
    System.out.println(group.subject() + " (" + group.participants().size() + " membros)");
    for (GroupParticipant p : group.participants()) {
        System.out.println("  " + p.name() + (p.isAdmin() ? " (admin)" : ""));
    }
});
```

### Listar todos os grupos

```java
client.listGroups().thenAccept(grupos -> {
    for (Group g : grupos) {
        System.out.println(g.id() + ": " + g.subject());
    }
});
```

### Criar um grupo

```java
client.createGroup("Time de Vendas", List.of(
        "5511999999999@s.whatsapp.net",
        "5511988888888@s.whatsapp.net"
)).thenAccept(grupoNovo -> System.out.println("criado: " + grupoNovo.id()));
```

### Gerenciar participantes

```java
// Acao e um dos 4 valores de WhatsAppClient.GroupParticipantAction
client.updateGroupParticipants(groupJid, List.of(jid1, jid2),
        WhatsAppClient.GroupParticipantAction.ADD);

client.updateGroupParticipants(groupJid, List.of(jid1),
        WhatsAppClient.GroupParticipantAction.REMOVE);

client.updateGroupParticipants(groupJid, List.of(jid1),
        WhatsAppClient.GroupParticipantAction.PROMOTE);   // torna admin

client.updateGroupParticipants(groupJid, List.of(jid1),
        WhatsAppClient.GroupParticipantAction.DEMOTE);    // remove admin
```

### Outras operações de grupo

```java
client.updateGroupSubject(groupJid, "Novo nome do grupo");
client.updateGroupDescription(groupJid, "Nova descricao do grupo");
client.leaveGroup(groupJid);

client.getGroupInviteCode(groupJid).thenAccept(codigo -> {
    if (codigo != null) {
        System.out.println("https://chat.whatsapp.com/" + codigo);
    }
});
```

## 8. Múltiplas instâncias

```java
List<String> numeros = List.of("vendas", "suporte", "financeiro");
List<WhatsAppClient> clientes = new java.util.ArrayList<>();

for (String id : numeros) {
    WhatsAppClient c = WhatsAppClient.builder()
            .instanceId(id)
            .sessionDirectory("./sessions/" + id)
            .build();
    clientes.add(c);

    c.addMessageListener(m -> System.out.println("[" + id + "] " + m.senderId() + ": " + m.text()));
    c.addConnectionStateListener((state, cause, detail) ->
            System.out.println("[" + id + "] estado: " + state));
}

Runtime.getRuntime().addShutdownHook(new Thread(() -> clientes.forEach(WhatsAppClient::close)));

clientes.forEach(WhatsAppClient::connect);
```

Cada instância sobe seu próprio processo bridge (isolamento de falhas — veja o README
"Arquitetura"). `instanceId` e `sessionDirectory` precisam ser únicos entre si.

## 9. Tratamento de erros

```
WhatsAppException (RuntimeException - nao verificada)
├── ConnectionException         - NOT_CONNECTED, CONNECTION_FAILED
├── AuthenticationException     - AUTH_FAILED
├── TimeoutException             - TIMEOUT
├── MessageSendException         - SEND_FAILED, MEDIA_NOT_FOUND
├── InstanceException            - INSTANCE_NOT_FOUND, INSTANCE_ALREADY_EXISTS
├── UnsupportedFeatureException  - UNSUPPORTED_FEATURE
└── BridgeException              - INVALID_TOKEN, PROTOCOL_VERSION_MISMATCH, INVALID_MESSAGE,
                                    UNKNOWN_ACTION, INVALID_PAYLOAD, INTERNAL_ERROR, UNKNOWN
```

```java
// Padrao assincrono (recomendado - a maior parte da API e assincrona)
client.sendText(chatId, "oi").exceptionally(ex -> {
    Throwable causa = ex.getCause() != null ? ex.getCause() : ex; // CompletableFuture embrulha em CompletionException
    if (causa instanceof MessageSendException mse) {
        System.err.println("falha ao enviar [" + mse.errorCode().orElse(null) + "]: " + mse.getMessage());
    } else if (causa instanceof ConnectionException) {
        System.err.println("nao conectado: " + causa.getMessage());
    }
    return null;
});

// Padrao sincrono (quando voce chama .get()/.join() diretamente)
try {
    String messageId = client.sendText(chatId, "oi").get(10, TimeUnit.SECONDS);
} catch (java.util.concurrent.ExecutionException e) {
    Throwable causa = e.getCause();
    if (causa instanceof WhatsAppException wae) {
        System.err.println(wae.instanceId().orElse("?") + ": " + wae.getMessage());
        System.err.println("detalhes: " + wae.details());
    }
} catch (java.util.concurrent.TimeoutException | InterruptedException e) {
    // tempo esgotado ou thread interrompida
}

// Erros que nao sao resposta direta de uma chamada (ex.: falha ao processar um evento
// recebido do bridge) chegam SOMENTE pelo ErrorListener, nunca por uma excecao lancada
// em outro lugar do seu codigo:
client.addErrorListener(error -> {
    log.error("erro assincrono do SDK: {}", error.getMessage(), error);
});
```

## 10. Modelos de dados (referência de campos)

Todos são `record`s Java — cada campo é um método de acesso (`campo()`), sem setters.

**`Message`**: `messageId`, `instanceId`, `chatId`, `senderId`, `timestamp` (long, epoch
segundos — use `timestampAsInstant()` para `java.time.Instant`), `text`, `type` (`MessageType`),
`isGroup`, `isFromMe`, `pushName`, `quotedMessage` (`QuotedMessage`, pode ser `null`), `rawData`
(`JsonNode` bruto do Baileys).

**`MessageType`** (enum): `TEXT`, `IMAGE`, `VIDEO`, `AUDIO`, `DOCUMENT`, `STICKER`, `LOCATION`,
`CONTACT`, `REACTION`, `POLL`, `INTERACTIVE_REPLY`, `PROTOCOL`, `UNKNOWN`.

**`QuotedMessage`**: `messageId`, `senderId`, `text`.

**`Contact`**: `jid`, `name`, `status`, `profilePictureUrl`, `onWhatsApp` (boolean).

**`Group`**: `id`, `subject`, `owner`, `description`, `creationTimestamp` (long, epoch
segundos), `participants` (`List<GroupParticipant>`, imutável), `rawData` (`JsonNode`).

**`GroupParticipant`**: `id`, `name`, `isAdmin` (boolean), `isSuperAdmin` (boolean).

**`MediaContent`**: `data` (`byte[]`), `mimetype` (`String`).

**`ConnectionState`** (enum): `DISCONNECTED`, `CONNECTING`, `QR_PENDING`,
`PAIRING_CODE_PENDING`, `CONNECTED`, `RECONNECTING`, `LOGGED_OUT`, `FAILED`.

**`DisconnectCause`** (enum): `USER_LOGOUT`, `CONNECTION_LOST`, `WHATSAPP_RESTART`,
`AUTH_FAILURE`, `BRIDGE_CRASH` (só ocorre no lado Java), `UNKNOWN`.

**`BridgeErrorCode`** (enum, em `com.angatusistemas.whatsapp.protocol`): `INVALID_TOKEN`,
`PROTOCOL_VERSION_MISMATCH`, `INVALID_MESSAGE`, `UNKNOWN_ACTION`, `INSTANCE_NOT_FOUND`,
`INSTANCE_ALREADY_EXISTS`, `NOT_CONNECTED`, `CONNECTION_FAILED`, `AUTH_FAILED`, `SEND_FAILED`,
`TIMEOUT`, `UNSUPPORTED_FEATURE`, `MEDIA_NOT_FOUND`, `INVALID_PAYLOAD`, `INTERNAL_ERROR`,
`UNKNOWN`.

## 11. Exemplo completo: bot com comandos

Programa único demonstrando conexão, QR Code, comandos de texto, envio de mídia, reação,
consulta de grupo e tratamento de erros — mais completo que `examples/simple-bot`.

```java
package com.example;

import com.angatusistemas.whatsapp.ConnectionState;
import com.angatusistemas.whatsapp.WhatsAppClient;
import com.angatusistemas.whatsapp.model.Group;
import com.angatusistemas.whatsapp.request.SendReactionRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

public class CommandBot {
    public static void main(String[] args) throws Exception {
        Path sessionDir = Path.of("./sessions/command-bot");
        WhatsAppClient client = WhatsAppClient.builder()
                .instanceId("command-bot")
                .sessionDirectory(sessionDir)
                .displayName("Angatu Command Bot")
                .build();

        Runtime.getRuntime().addShutdownHook(new Thread(client::close));

        client.addConnectionStateListener((state, cause, detail) -> {
            System.out.println("[estado] " + state + (cause != null ? " (" + cause + ")" : ""));
        });

        client.addQrCodeListener(qr -> {
            byte[] png = client.getQrCodePng();
            if (png != null) {
                try {
                    Files.createDirectories(sessionDir);
                    Files.write(sessionDir.resolve("qrcode.png"), png);
                    System.out.println("QR Code salvo em " + sessionDir.resolve("qrcode.png"));
                } catch (IOException e) {
                    System.err.println("falha ao salvar QR: " + e.getMessage());
                }
            }
        });

        client.addErrorListener(error ->
                System.err.println("[erro] " + error.getMessage()));

        client.addMessageListener(message -> {
            if (message.isFromMe() || message.text() == null) {
                return;
            }
            String text = message.text().trim();

            if (text.equalsIgnoreCase("!ping")) {
                client.sendText(message.chatId(), "pong!", message.messageId());

            } else if (text.equalsIgnoreCase("!reagir")) {
                client.sendReaction(SendReactionRequest.builder()
                        .chatId(message.chatId())
                        .messageId(message.messageId())
                        .emoji("✅")
                        .fromMe(false)
                        .build());

            } else if (text.equalsIgnoreCase("!grupo") && message.isGroup()) {
                client.getGroupMetadata(message.chatId()).thenAccept((Group grupo) -> {
                    String resumo = grupo.subject() + " - " + grupo.participants().size() + " membros";
                    client.sendText(message.chatId(), resumo);
                }).exceptionally(ex -> {
                    client.sendText(message.chatId(), "nao foi possivel consultar o grupo");
                    return null;
                });

            } else if (text.startsWith("!eco ")) {
                client.sendText(message.chatId(), text.substring(5), message.messageId())
                        .exceptionally(ex -> {
                            System.err.println("falha ao responder: " + ex.getMessage());
                            return null;
                        });
            }
        });

        client.connect().join();
        System.out.println("bot em execucao - Ctrl+C para encerrar");
        new CountDownLatch(1).await();
    }
}
```

## 12. CLI: exemplos de linha de comando

```bash
# Parear um numero novo (mostra QR Code em ASCII no terminal, fica rodando)
java -jar angatu-whatsapp-cli.jar connect --instance-id vendas --session-dir ./sessions/vendas

# Parear via codigo (sem escanear QR)
java -jar angatu-whatsapp-cli.jar connect --instance-id vendas --session-dir ./sessions/vendas \
    --pairing-phone 5511999999999

# Consultar o estado atual de uma sessao ja pareada
java -jar angatu-whatsapp-cli.jar status --instance-id vendas --session-dir ./sessions/vendas

# Enviar uma mensagem avulsa
java -jar angatu-whatsapp-cli.jar send --instance-id vendas --session-dir ./sessions/vendas \
    --to 5511999999999@s.whatsapp.net --text "Ola do CLI!"

# Invalidar a sessao (logout)
java -jar angatu-whatsapp-cli.jar logout --instance-id vendas --session-dir ./sessions/vendas
```

## 13. Referência rápida de métodos

### `WhatsAppClient` (`com.angatusistemas.whatsapp`)

| Método | Retorno |
|---|---|
| `static Builder builder()` | `Builder` |
| `CompletableFuture<Void> connect()` | — |
| `CompletableFuture<Void> disconnect()` | — |
| `CompletableFuture<Void> logout()` | — |
| `CompletableFuture<String> requestPairingCode(String phoneNumber)` | código de pareamento |
| `void close()` | — |
| `String getInstanceId()` | — |
| `ConnectionState getConnectionState()` | — |
| `String getCurrentQrCode()` | conteúdo bruto do QR, ou `null` |
| `byte[] getQrCodePng()` / `getQrCodePng(int sizePixels)` | PNG, ou `null` |
| `void addMessageListener(MessageListener)` | — |
| `void addMessageUpdatedListener(MessageUpdatedListener)` | — |
| `void addMessageDeletedListener(MessageDeletedListener)` | — |
| `void addReactionListener(ReactionListener)` | — |
| `void addConnectionStateListener(ConnectionStateListener)` | — |
| `void addQrCodeListener(QrCodeListener)` | — |
| `void addGroupUpdateListener(GroupUpdateListener)` | — |
| `void addPresenceListener(PresenceListener)` | — |
| `void addErrorListener(ErrorListener)` | — |
| `CompletableFuture<String> sendText(String to, String text)` | messageId |
| `CompletableFuture<String> sendText(String to, String text, String quotedMessageId)` | messageId |
| `CompletableFuture<String> sendImage(SendImageRequest)` | messageId |
| `CompletableFuture<String> sendVideo(SendVideoRequest)` | messageId |
| `CompletableFuture<String> sendAudio(SendAudioRequest)` | messageId |
| `CompletableFuture<String> sendDocument(SendDocumentRequest)` | messageId |
| `CompletableFuture<String> sendSticker(SendStickerRequest)` | messageId |
| `CompletableFuture<String> sendLocation(SendLocationRequest)` | messageId |
| `CompletableFuture<String> sendContact(SendContactRequest)` | messageId |
| `CompletableFuture<Void> sendReaction(SendReactionRequest)` | — |
| `CompletableFuture<String> editMessage(EditMessageRequest)` | messageId da edição |
| `CompletableFuture<Void> deleteMessage(DeleteMessageRequest)` | — |
| `CompletableFuture<MediaContent> downloadMedia(String chatId, String messageId)` | — |
| `CompletableFuture<Contact> getContact(String jid)` | — |
| `CompletableFuture<Group> getGroupMetadata(String groupJid)` | — |
| `CompletableFuture<List<Group>> listGroups()` | — |
| `CompletableFuture<Group> createGroup(String subject, List<String> participants)` | — |
| `CompletableFuture<JsonNode> updateGroupParticipants(String groupJid, List<String> participants, GroupParticipantAction action)` | — |
| `CompletableFuture<Void> updateGroupSubject(String groupJid, String subject)` | — |
| `CompletableFuture<Void> updateGroupDescription(String groupJid, String description)` | — |
| `CompletableFuture<Void> leaveGroup(String groupJid)` | — |
| `CompletableFuture<String> getGroupInviteCode(String groupJid)` | código do convite, ou `null` |

### `WhatsAppClient.Builder`

| Método | Obrigatório | Padrão |
|---|---|---|
| `instanceId(String)` | Sim | — |
| `sessionDirectory(String)` / `sessionDirectory(Path)` | Sim | — |
| `displayName(String)` | Não | padrão do bridge |
| `markOnlineOnConnect(boolean)` | Não | `false` |
| `minReconnectDelay(Duration)` | Não | 1s |
| `maxReconnectDelay(Duration)` | Não | 30s |
| `maxReconnectAttempts(int)` | Não | 10 |
| `nodeVersion(String)` | Não | versão LTS pinada pelo SDK |
| `bridgeHost(String)` | Não | `127.0.0.1` |
| `bridgePort(int)` | Não | porta efêmera do SO |
| `WhatsAppClient build()` | — | — |

### `WhatsAppClient.GroupParticipantAction` (enum aninhado)

`ADD`, `REMOVE`, `PROMOTE`, `DEMOTE`.

### Builders de requisição (`com.angatusistemas.whatsapp.request`)

Todos seguem `Xxx.builder()....build()`, exceto `SendLocationRequest.builder(double, double)`
que recebe as coordenadas obrigatórias como argumentos do próprio `builder(...)`.

| Classe | Campos (**negrito** = obrigatório) |
|---|---|
| `SendImageRequest` | **to**, **filePath**, caption, quotedMessageId |
| `SendVideoRequest` | **to**, **filePath**, caption, gifPlayback, quotedMessageId |
| `SendAudioRequest` | **to**, **filePath**, ptt, quotedMessageId |
| `SendDocumentRequest` | **to**, **filePath**, **fileName**, **mimetype**, caption, quotedMessageId |
| `SendStickerRequest` | **to**, **filePath**, quotedMessageId |
| `SendLocationRequest` | **to**, **latitude**\*, **longitude**\*, name, address, quotedMessageId |
| `SendContactRequest` | **to**, **displayName**, **phoneNumber**, organization, quotedMessageId |
| `SendReactionRequest` | **chatId**, **messageId**, emoji, fromMe, participant |
| `EditMessageRequest` | **chatId**, **messageId**, **text**, fromMe (padrão `true`), participant |
| `DeleteMessageRequest` | **chatId**, **messageId**, fromMe (padrão `true`), participant |

\* `latitude`/`longitude` são passados em `SendLocationRequest.builder(lat, lon)`, não em um setter.
