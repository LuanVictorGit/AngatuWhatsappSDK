---
tags:
  - biblioteca
  - whatsapp
  - exemplos
versao: 2.0.0-SNAPSHOT
---

# Exemplos — Angatu WhatsApp SDK

Um exemplo por recurso da API. Visão geral, instalação e regras de ritmo no [README](README.md).
Em todos os trechos, `client` é um `WhatsAppClient` já conectado e `chat` é um número com DDI
(`"5562999998888"`) ou um JID (`"5562999998888@s.whatsapp.net"`, `"1203...@g.us"`).

> [!TIP]
> Todo método que fala com o WhatsApp entra na fila do número e devolve `CompletableFuture`. O
> future termina quando a interação de fato aconteceu, o que pode levar segundos (digitando,
> intervalo entre mensagens). Encadeie com `thenAccept`/`exceptionally` em vez de bloquear.

## Conexão

### Construir o cliente com todas as opções

```java
WhatsAppClient client = WhatsAppClient.builder()
        .instanceId("vendas")                                    // único na JVM
        .sessionDirectory(Path.of("/data/whatsapp/vendas"))      // uma pasta por número
        .deviceName("Loja Centro")                               // aparece em Aparelhos conectados
        .maxLoginCycles(5)                                       // ciclos de QR antes de desistir
        .reconnectDelay(Duration.ofSeconds(1), Duration.ofSeconds(30))
        .pacing(PacingOptions.defaults())
        .humanization(HumanizationOptions.defaults())
        .calls(CallOptions.defaults())
        .includeRawData(false)                                   // true anexa a mensagem bruta (pesado)
        .bridge(options -> options.withMaxHeapMb(512))
        .build();
```

### QR Code

```java
client.addQrCodeListener(qr -> {
    byte[] png = client.getQrCodePng(400);     // para mostrar numa tela de operador autenticada
    String terminal = client.getQrCodeText();  // para console
    int cycle = qr.cycle();                    // 1, 2, 3... cada ciclo dura cerca de 2 minutos
});
client.connect();
```

> [!CAUTION]
> O QR Code é uma credencial em trânsito: quem escaneia pareia o seu número. Mostre só para um
> operador autenticado; nunca em página pública, log ou e-mail.

### Código de pareamento

```java
client.requestPairingCode("5562999998888")
        .thenAccept(code -> System.out.println(code.substring(0, 4) + "-" + code.substring(4)));

client.requestPairingCode("5562999998888", "ANGATU26");   // código próprio: 8 caracteres, A-Z e 0-9
```

### Estado da conexão

```java
client.addConnectionStateListener((state, cause, detail) -> {
    switch (state) {
        case CONNECTED -> System.out.println("online");
        case RECONNECTING -> System.out.println("reconectando: " + cause);
        case LOGGED_OUT -> System.out.println("desconectado no celular: parear de novo");
        case FAILED -> System.out.println("recusado: " + detail);
        case DISCONNECTED -> {
            if (cause == DisconnectCause.LOGIN_TIMEOUT) {
                System.out.println("ninguém leu o QR; chame connect() de novo");
            }
        }
        default -> { }
    }
});
boolean online = client.isConnected();
```

### Sessão salva

```java
SessionStatus status = client.sessionStatus();     // ou WhatsAppClient.inspectSession(pasta), sem cliente
if (status.canResume()) {
    System.out.println("Volta sem QR, número " + status.phoneNumber());
}
```

### Pausar, retomar, sair, encerrar

```java
client.disconnect().join();   // fecha a conexão com o WhatsApp, mantém a sessão
client.connect().join();      // volta sem QR
client.logout().join();       // desconecta o aparelho da conta e apaga a credencial
client.close();               // encerra o cliente (e a ponte, se for o último)
```

## Mensagens

### Texto, resposta e tempo de digitação

```java
client.sendText(chat, "Olá! Seu pedido saiu para entrega.")
        .thenAccept(id -> System.out.println("enviada: " + id));

client.sendText(SendTextRequest.of(chat, "Sim, temos em estoque.")
        .quoting(message.messageId())               // responde citando
        .withTyping(Duration.ofSeconds(4)));        // 4 s de "digitando..." só nesta
```

### Mídia

```java
client.sendImage(SendImageRequest.of(chat, Path.of("catalogo.jpg")).withCaption("Novidades da semana"));
client.sendVideo(SendVideoRequest.of(chat, Path.of("demo.mp4")).withCaption("Como usar"));
client.sendVideo(SendVideoRequest.of(chat, Path.of("risada.mp4")).asGif());
client.sendAudio(SendAudioRequest.voiceNote(chat, Path.of("recado.ogg")));     // "gravando áudio..."
client.sendAudio(SendAudioRequest.of(chat, Path.of("musica.mp3")));
client.sendDocument(SendDocumentRequest.of(chat, Path.of("nota.pdf"), "application/pdf")
        .withFileName("Nota fiscal 1234.pdf").withCaption("Segue a nota"));
client.sendSticker(SendStickerRequest.of(chat, Path.of("figurinha.webp")));
```

O arquivo é lido pelo processo da ponte, na mesma máquina; o caminho é conferido antes de entrar
na fila.

### Localização e contato

```java
client.sendLocation(SendLocationRequest.of(chat, -16.6869, -49.2648).withName("Loja Centro", "Av. Goiás, 100"));
client.sendContact(SendContactRequest.of(chat, "Suporte Angatu", "5562988887777").withOrganization("Angatu Sistemas"));
```

### Botões

```java
client.sendButtons(SendButtonsRequest.of(chat, "Como prefere receber?",
                Button.of("entrega", "Entrega"),
                Button.of("retirada", "Retirar na loja"))
        .withTitle("Pedido 1234")
        .withFooter("Responda tocando numa opção"));

client.sendButtons(SendButtonsRequest.of(chat, "Escolha o horário:",
                Button.of("9", "9h"), Button.of("10", "10h"), Button.of("14", "14h"), Button.of("16", "16h"))
        .withStyle(ButtonStyle.TEXT));        // menu numerado, até 10 opções, funciona em qualquer WhatsApp

client.addButtonListener(click -> {
    String escolha = click.buttonId();        // "entrega", "retirada", "9", ...
    String rotulo = click.label();
    String mensagemOriginal = click.menuMessageId();
    client.sendText(click.chatId(), "Anotado: " + rotulo);
});
```

### Enquete

```java
client.sendPoll(chat, "Qual sabor?", List.of("Calabresa", "Marguerita", "Frango"), 1);
client.addPollVoteListener(vote ->
        System.out.println(vote.voterId() + " escolheu " + vote.selectedOptions() + " na enquete " + vote.pollMessageId()));
```

### Reagir, editar, apagar, encaminhar, favoritar

```java
MessageRef ref = MessageRef.of(message);                  // a partir de uma mensagem recebida
MessageRef mine = MessageRef.mine(chat, messageIdEnviado); // uma mensagem que esta conta enviou

client.sendReaction(ref, "👍");
client.removeReaction(ref);
client.editMessage(mine, "Correção: o horário é 15h.");    // só dentro da janela do WhatsApp
client.deleteMessage(mine);                                 // apagar para todos
client.forwardMessage(ref, "5562977776666");
client.starMessage(ref, true);
```

### Baixar mídia recebida

```java
client.addMessageListener(message -> {
    if (message.hasMedia()) {
        client.downloadMedia(message, Path.of("recebidos", message.messageId()))
                .thenAccept(file -> System.out.println(file.path() + " " + file.mimetype() + " " + file.size()));
    }
});
```

## Presença e visto

```java
client.sendTyping(chat, Duration.ofSeconds(5));    // "digitando..." por 5 s, sem enviar nada
client.sendPresence(chat, Presence.RECORDING);
client.setOnline(true);
client.subscribePresence(chat);                    // passa a receber o online/digitando do contato
client.addPresenceListener(update -> System.out.println(update.participant() + " " + update.presence()));

client.markRead(message);                          // uma mensagem (e as anteriores não lidas)
client.markChatRead(chat);                         // todas as não lidas da conversa
```

## Chamadas

```java
WhatsAppClient.builder()
        .calls(CallOptions.rejectAll()
                .withRejectDelay(Duration.ofSeconds(2), Duration.ofSeconds(5))
                .withReply("Não atendemos ligações. Mande sua dúvida por aqui!"));

client.addCallListener(call -> System.out.println((call.isVideo() ? "vídeo" : "voz") + " de " + call.chatId()));
client.rejectCall(call);                           // recusa manual, sem a opção automática
```

## Contatos

```java
client.checkNumber("5562999998888").thenAccept(check -> {
    if (check.exists()) {
        client.sendText(check.jid(), "Olá!");        // envie para o JID devolvido (nono dígito resolvido)
    }
});
client.contactExists("5562999998888").thenAccept(exists -> { });
client.checkNumbers(List.of("5562999998888", "5511988887777"));   // até 50 por chamada
client.getProfilePicture(chat).thenAccept(url -> url.ifPresent(System.out::println));
client.getContactStatus(chat).thenAccept(status -> System.out.println(status.status()));
client.getBusinessProfile(chat).thenAccept(profile -> profile.ifPresent(p -> System.out.println(p.category())));
client.getContact(chat);                           // 3 consultas: prefira os métodos acima
client.blockContact(chat);
client.unblockContact(chat);
client.getBlocklist().thenAccept(System.out::println);
```

## A própria conta

```java
client.getMe().thenAccept(me -> System.out.println(me.phoneNumber() + " " + me.name()));
client.updateProfileName("Loja Centro");
client.updateProfileStatus("Atendimento de 8h às 18h");
client.updateProfilePicture(Path.of("logo.jpg"));
client.removeProfilePicture();
client.getPrivacySettings().thenAccept(System.out::println);
client.updatePrivacy(PrivacySetting.LAST_SEEN, "contacts");   // valores aceitos: PrivacySetting.accepted()
```

## Conversas

```java
client.muteChat(chat, Duration.ofHours(8));        // null silencia para sempre
client.unmuteChat(chat);
client.pinChat(chat, true);
client.archiveChat(chat, true);                    // precisa de uma mensagem recente na conversa
client.markChatUnread(chat);
client.setDisappearingMessages(chat, Duration.ofDays(7));   // 1, 7 ou 90 dias; null desliga
```

## Grupos

```java
client.groupExists("1203630000000@g.us").thenAccept(check ->
        System.out.println(check.exists() + " membro=" + check.isMember() + " " + check.subject()));

client.createGroup("Equipe Vendas", List.of("5562999998888", "5562977776666"))
        .thenAccept(group -> System.out.println(group.id()));
client.getGroupMetadata(groupJid).thenAccept(group -> group.participants().forEach(p -> System.out.println(p.id())));
client.listGroups();
client.updateGroupParticipants(groupJid, List.of("5562966665555"), ParticipantAction.ADD)
        .thenAccept(results -> results.forEach(r -> System.out.println(r.jid() + " " + r.succeeded())));
client.updateGroupSubject(groupJid, "Equipe Vendas 2026");
client.updateGroupDescription(groupJid, "Avisos da equipe");
client.updateGroupPicture(groupJid, Path.of("capa.jpg"));
client.setGroupAnnounceOnly(groupJid, true);       // só administradores enviam
client.setGroupLocked(groupJid, true);             // só administradores editam o grupo
client.getGroupInviteCode(groupJid).thenAccept(code -> code.ifPresent(c -> System.out.println("https://chat.whatsapp.com/" + c)));
client.revokeGroupInviteCode(groupJid);
client.getGroupInviteInfo("https://chat.whatsapp.com/AbCdEf123456");
client.joinGroup("AbCdEf123456");
client.leaveGroup(groupJid);
client.addGroupUpdateListener(update -> System.out.println(update.action() + " " + update.participants()));
```

## Eventos de mensagem

```java
client.addMessageListener(message -> {
    // só mensagens novas; message.isFromMe() é true para o que o dono enviou pelo celular
});
client.addMessageUpdatedListener(update -> System.out.println(update.messageId() + " " + update.status()));
client.addMessageDeletedListener((chatId, messageId) -> System.out.println("apagada: " + messageId));
client.addReactionListener(reaction -> System.out.println(reaction.emoji() + " removida=" + reaction.removed()));
client.addErrorListener(error -> System.out.println(error.errorCode() + ": " + error.getMessage()));
```

## Fila e ritmo

```java
client.setPacing(PacingOptions.defaults()
        .withGap(Duration.ofSeconds(8), Duration.ofSeconds(25))
        .withMaxSendsPerMinute(5)
        .withMaxReadsPerMinute(20)
        .withInteractionInterval(Duration.ofMillis(800))   // mínimo 500 ms
        .withMaxPendingAge(Duration.ofHours(6)));

client.setHumanization(HumanizationOptions.defaults()
        .withTyping(Duration.ofSeconds(2), Duration.ofSeconds(10))
        .withTypingSpeed(40)
        .withReadReceipts(ReadReceiptMode.AUTO)
        .withReadDelay(Duration.ofSeconds(2), Duration.ofSeconds(6))
        .withAlwaysOnline(false)
        .withOnlineHoldAfterSend(Duration.ofSeconds(45))
        .withAutoSubscribePresence(false));

for (PendingInteraction pending : client.pendingInteractions()) {
    System.out.println(pending.id() + " " + pending.action() + " " + pending.chatId() + " " + pending.notBefore());
}
client.cancelInteraction(idDaInteracao);
client.clearQueue();
```

## Erros

```java
client.sendText(chat, "oi").exceptionally(error -> {
    Throwable cause = error instanceof CompletionException ? error.getCause() : error;
    if (cause instanceof WhatsAppException failure) {
        switch (failure.errorCode()) {
            case NOT_CONNECTED -> System.out.println("sem conexão");
            case RATE_LIMITED -> System.out.println("consultas demais neste minuto");
            case TIMEOUT -> System.out.println("sem resposta: não dá para saber se saiu");
            case EXPIRED -> System.out.println("ficou tempo demais na fila");
            default -> System.out.println(failure.getMessage());
        }
    }
    return null;
});
```

## Monitorar memória

```java
client.bridgeStats().thenAccept(stats ->
        System.out.printf("ponte pid %d, RSS %d MB, heap %d MB, %d números%n",
                stats.pid(), stats.rssBytes() >> 20, stats.heapUsedBytes() >> 20, stats.instances()));
```
