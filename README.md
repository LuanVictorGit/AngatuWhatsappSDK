---
tags:
  - biblioteca
  - whatsapp
  - java
status: ativo
versao: 2.0.0-SNAPSHOT
---

# Angatu WhatsApp SDK

Biblioteca Java para automatizar WhatsApp com comportamento de gente: uma fila que espaça cada
interação, "digitando..." antes de responder, visto azul na hora certa, conta sempre online e
recusa de chamadas — com pouca memória e reconexão que não desiste.

Por baixo roda o [Baileys](https://github.com/WhiskeySockets/Baileys) num processo Node.js que a
própria biblioteca baixa, inicia e encerra. Quem usa a SDK escreve só Java.

> [!WARNING]
> O Baileys é um cliente **não oficial** do WhatsApp Web. Automatizar um número por ele viola os
> termos do WhatsApp e o número pode ser banido — principalmente com volume alto, mensagens que
> ninguém pediu ou padrão de máquina. A SDK reduz o padrão de máquina; ela não torna aceitável
> mandar o mesmo texto para milhares de desconhecidos. Use um número descartável enquanto
> desenvolve. Para uso comercial crítico, existe a API oficial (WhatsApp Cloud API) da Meta.

## O que ela faz

- **Fila de interações em disco.** Tudo que vai ao WhatsApp — mensagem, presença, visto, consulta,
  ação em grupo — passa por uma fila por número, uma coisa de cada vez, com no mínimo 500 ms entre
  elas. A fila mora em SQLite, então mil mensagens agendadas ocupam disco, não memória, e
  sobrevivem a uma queda do processo.
- **Ritmo humano.** Antes de cada mensagem: marca a conversa como lida, mostra "digitando..." por
  um tempo proporcional ao texto, envia e espera um intervalo aleatório (4 a 12 s por padrão) antes
  da próxima. Áudio de voz mostra "gravando áudio...".
- **Só mensagens novas.** Nada de histórico: a biblioteca entrega apenas o que chega depois que o
  cliente sobe, e nunca pede conversas antigas ao celular.
- **Conexão estável.** QR Code ou código de pareamento, credencial gravada de forma durável com
  backup automático, reconexão com espera crescente, retomada sem QR depois de reiniciar a
  aplicação e limpeza de processo órfão de uma execução anterior.
- **Pouca memória.** Um único processo Node atende todos os números da aplicação; caches do
  protocolo limitados e sem timer; mídia baixada direto para arquivo.
- **API completa.** Texto, imagem, vídeo, áudio, documento, figurinha, localização, contato,
  botões (com evento de qual foi apertado), enquetes (com voto decifrado), reações, edição, apagar
  para todos, encaminhar; grupos, convites, perfil, privacidade, bloqueio; verificar se um número
  ou um grupo existe.

## Requisitos

- **Java 25** (a biblioteca é compilada para o Java 25).
- **Node.js 22 ou mais novo** em tempo de execução. Não precisa instalar: se a máquina não tiver,
  a SDK baixa o Node oficial de nodejs.org, confere o SHA-256 e guarda em cache.
- Nada de Node na hora de compilar o seu projeto: a ponte já vem dentro do jar.

## Instalação

As versões saem pelo JitPack, pelo hash do commit (o repositório não publica tags). Pegue o hash
mais recente da branch `main` e use como versão:

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.LuanVictorGit.AngatuWhatsappSDK</groupId>
    <artifactId>angatu-whatsapp</artifactId>
    <version>HASH_DO_COMMIT</version>
</dependency>
```

```groovy
repositories { maven { url 'https://jitpack.io' } }
dependencies { implementation 'com.github.LuanVictorGit.AngatuWhatsappSDK:angatu-whatsapp:HASH_DO_COMMIT' }
```

A SDK traz junto a [AngatuLibraries](https://github.com/LuanVictorGit/AngatuLibraries) (SQLite da
fila, JSON e console), o driver SQLite, HikariCP, Gson, Java-WebSocket e ZXing.

## Primeiros passos

```java
WhatsAppClient client = WhatsAppClient.builder()
        .instanceId("atendimento")
        .sessionDirectory(Path.of("/data/whatsapp/atendimento"))
        .build();

client.addQrCodeListener(qr -> System.out.println(client.getQrCodeText()));
client.addConnectionStateListener((state, cause, detail) -> System.out.println(state + " " + cause));
client.addMessageListener(message -> {
    if (!message.isFromMe() && message.hasText()) {
        client.sendText(message.chatId(), "Recebi: " + message.text());
    }
});

Runtime.getRuntime().addShutdownHook(new Thread(client::close));
client.connect().join();
```

- `connect()` termina quando a ponte aceitou a tentativa, **não** quando o WhatsApp autenticou. O
  progresso real (`QR_PENDING`, `CONNECTED`, `FAILED`...) chega pelo `ConnectionStateListener`.
- Todo envio devolve `CompletableFuture` e nunca trava quem chamou. O future termina quando a
  mensagem de fato saiu, depois da sua vez na fila.
- Os listeners de um cliente rodam em ordem, num virtual thread só dele. Trabalho pesado dentro de
  um listener atrasa os próximos eventos daquele número: mande para outra thread.

### Parear com código em vez de QR

```java
String code = client.requestPairingCode("5562999998888").join();   // ex.: "K7M2Q9XA"
System.out.println("Digite no celular: " + code.substring(0, 4) + "-" + code.substring(4));
```

No celular: WhatsApp > Aparelhos conectados > Conectar um aparelho > Conectar com número de
telefone. O mesmo código continua válido enquanto o WhatsApp renova a conexão por baixo, que
acontece a cada dois minutos durante o pareamento.

Sem leitura do QR nem do código, a tentativa para depois de 5 ciclos (cerca de 10 minutos) com
`DisconnectCause.LOGIN_TIMEOUT`; chame `connect()` de novo para recomeçar. Ajuste com
`maxLoginCycles(n)` no builder.

### Reiniciar sem pedir QR de novo

A sessão fica na pasta `sessionDirectory`. Enquanto ela existir, a aplicação volta conectada
depois de reiniciar, sem QR. Para saber antes de conectar:

```java
SessionStatus status = WhatsAppClient.inspectSession(Path.of("/data/whatsapp/atendimento"));
if (status.canResume()) {
    // PAIRED: reconecta direto, número em status.phoneNumber()
}
```

| Estado | Significa |
|---|---|
| `PAIRED` | Credencial completa; reconecta sem QR. |
| `NOT_PAIRED` | Pasta nova ou sessão encerrada; vai pedir QR ou código. |
| `PAIRING_INCOMPLETE` | Um pareamento começou e não terminou; a SDK descarta e recomeça o login. |
| `CORRUPTED` | O `creds.json` está ilegível; se houver backup (`backupAvailable`), ele é restaurado ao conectar. |

A cada conexão aberta a SDK grava um backup da credencial (`creds.backup.json`). Ela só apaga a
credencial quando o próprio WhatsApp a revoga (aparelho desconectado no celular).

## Ritmo humano: a fila

```text
send*() / markRead() / getContact() ...
        │
        ▼
 ┌──────────────────────────────┐   SQLite (tabela queuedinteractions)
 │ InteractionQueue por número  │── uma linha por interação pendente
 └──────────────────────────────┘
        │  uma de cada vez, ≥ 500 ms entre stanzas
        ▼
 visto ─► "digitando..." ─► mensagem ─► intervalo aleatório ─► próxima
```

- **Prioridade:** recusa de chamada > visto > presença > mensagens e ações > consultas > keep-alive.
  Uma ligação é recusada mesmo com uma campanha de mil mensagens na fila.
- **Limites padrão:** intervalo de 4 a 12 s entre mensagens, no máximo 10 mensagens e 30 consultas
  por minuto. Uma interação que espera mais de 24 horas expira (`withMaxPendingAge`).
- **Durável:** se o processo cair, o que estava pendente roda quando o número voltar. O que estava
  saindo no instante da queda é descartado, porque mensagem duplicada é pior que mensagem perdida.
- **Controle:** `pendingInteractions()`, `cancelInteraction(id)` e `clearQueue()`.

```java
client.setPacing(PacingOptions.defaults()
        .withGap(Duration.ofSeconds(6), Duration.ofSeconds(20))
        .withMaxSendsPerMinute(6));

client.setHumanization(HumanizationOptions.defaults()
        .withTyping(Duration.ofSeconds(2), Duration.ofSeconds(8))   // limites do "digitando..."
        .withTypingSpeed(35)                                        // ms por caractere
        .withReadReceipts(ReadReceiptMode.ON_REPLY)                 // visto ao responder
        .withAlwaysOnline(true));                                   // online o tempo todo
```

| Recurso | Como |
|---|---|
| "Digitando..." com tempo configurável | automático em todo envio; `SendTextRequest.of(to, texto).withTyping(Duration.ofSeconds(5))` fixa o tempo de uma mensagem; `sendTyping(chat, duração)` mostra sem enviar nada |
| Visto azul | `ReadReceiptMode.ON_REPLY` (padrão: marca ao responder), `AUTO` (marca sozinho após 1–4 s), `MANUAL` (`markRead(message)`, `markChatRead(chat)`) ou `OFF`. Marcar uma conversa cobre todas as mensagens não lidas dela num único recibo, como o celular faz |
| Online permanente | `withAlwaysOnline(true)` (padrão). A SDK reafirma a presença a cada 4–6 minutos. Com `false`, a conta fica offline 30 s depois do último envio |
| Recusar chamadas | `.calls(CallOptions.rejectAll().withReply("Não atendemos ligações, mande uma mensagem!"))`. A recusa sai depois de 2 a 5 s, como quem olha o celular antes |

> [!NOTE]
> Enquanto a conta está online no WhatsApp Web, o celular do dono deixa de receber notificação
> das conversas. É o comportamento do próprio WhatsApp.

## Botões e enquetes

```java
client.sendButtons(SendButtonsRequest.of(chat, "Confirma o horário de amanhã, 15h?",
                Button.of("sim", "Confirmo"),
                Button.of("nao", "Preciso remarcar"))
        .withFooter("Clínica Exemplo"));

client.addButtonListener(click -> {
    // click.buttonId() = "sim" ou "nao"; click.chatId(); click.reply() é a mensagem da resposta
});
```

- `ButtonStyle.NATIVE` (padrão): botões interativos, até 3. A resposta chega com o id do botão.
- `ButtonStyle.TEXT`: menu numerado, até 10 opções, que qualquer versão do WhatsApp mostra. A
  SDK guarda o menu e reconhece a resposta pelo número ("2", "2.", "2)") ou pelo texto da opção,
  sem diferenciar maiúsculas e acentos. Use quando o aparelho do destinatário não exibir botões.
- Botão interativo é recurso de conta comercial: numa conta pessoal ele chama mais atenção que um
  menu em texto.

```java
client.sendPoll(chat, "Melhor dia para a entrega?", List.of("Segunda", "Quarta", "Sexta"), 1);
client.addPollVoteListener(vote -> System.out.println(vote.voterId() + " votou " + vote.selectedOptions()));
```

## Referência rápida

| Área | Métodos |
|---|---|
| Conexão | `connect`, `requestPairingCode`, `disconnect`, `logout`, `close`, `getConnectionState`, `isConnected`, `sessionStatus`, `inspectSession`, `getQrCode`, `getQrCodePng`, `getQrCodeText`, `bridgeStats` |
| Mensagens | `sendText`, `sendImage`, `sendVideo`, `sendAudio`, `sendDocument`, `sendSticker`, `sendLocation`, `sendContact`, `sendButtons`, `sendPoll`, `forwardMessage`, `sendReaction`, `removeReaction`, `editMessage`, `deleteMessage`, `starMessage`, `downloadMedia` |
| Presença e visto | `sendTyping`, `sendPresence`, `setOnline`, `subscribePresence`, `markRead`, `markChatRead`, `rejectCall` |
| Contatos | `checkNumber`, `checkNumbers`, `contactExists`, `getContact`, `getProfilePicture`, `getContactStatus`, `getBusinessProfile`, `blockContact`, `unblockContact`, `getBlocklist` |
| Conta | `getMe`, `updateProfileName`, `updateProfileStatus`, `updateProfilePicture`, `removeProfilePicture`, `getPrivacySettings`, `updatePrivacy` |
| Conversas | `muteChat`, `unmuteChat`, `pinChat`, `archiveChat`, `markChatUnread`, `setDisappearingMessages` |
| Grupos | `groupExists`, `getGroupMetadata`, `listGroups`, `createGroup`, `updateGroupParticipants`, `updateGroupSubject`, `updateGroupDescription`, `updateGroupPicture`, `setGroupAnnounceOnly`, `setGroupLocked`, `leaveGroup`, `getGroupInviteCode`, `revokeGroupInviteCode`, `getGroupInviteInfo`, `joinGroup` |
| Eventos | `addMessageListener`, `addMessageUpdatedListener`, `addMessageDeletedListener`, `addReactionListener`, `addConnectionStateListener`, `addQrCodeListener`, `addGroupUpdateListener`, `addPresenceListener`, `addCallListener`, `addButtonListener`, `addPollVoteListener`, `addErrorListener` |
| Fila | `pendingInteractions`, `cancelInteraction`, `clearQueue`, `setPacing`, `setHumanization`, `setCallOptions` |

Exemplo de cada método em [EXAMPLES.md](EXAMPLES.md).

> [!IMPORTANT]
> Leitura em volume é o que mais derruba número: uma tela que buscava a foto de 200 contatos ao
> abrir disparou 600 consultas em rajada e a conta foi suspensa. Prefira `checkNumber` (1 consulta,
> com cache de 24 h) e `getProfilePicture` (1 consulta) a `getContact` (3 consultas), e guarde
> localmente o que já buscou.

## Em produção

| O quê | Por quê |
|---|---|
| `sessionDirectory` num volume persistente (ex.: `/data/whatsapp/<id>`) | Sem isso, todo deploy pede QR de novo. Uma pasta por número, nunca compartilhada, nunca em NFS/SMB. A pasta é credencial: fora do git e da imagem. |
| `ANGATU_DB_PATH=/data/database.db` | Onde fica a fila (o mesmo banco da AngatuLibraries, se a aplicação já usa). |
| `ANGATU_WHATSAPP_HOME=/data/angatu-whatsapp` ou Node instalado na imagem | Senão o Node é baixado de novo a cada deploy. |
| `ANGATU_WHATSAPP_NODE_PATH` | Força um Node específico. |
| Chamar `close()` ao encerrar | Avisa a ponte para fechar o socket do WhatsApp com educação. Se o processo morrer sem isso, a ponte se encerra sozinha após 2 minutos sem cliente, e a próxima execução encerra a que tiver sobrado. |

Configurações da ponte, valem para a JVM inteira (o primeiro cliente decide):

```java
WhatsAppClient.builder()
        .bridge(options -> options.withMaxHeapMb(384).withLogLevel("warn"))
```

## Memória e estabilidade

- Um processo Node para todos os números. Cada número a mais custa um socket e uma fila, não
  outro runtime.
- O Node sobe com `--optimize-for-size`, geração jovem pequena e teto de heap (512 MB, ajustável):
  se algo fugir do controle, a ponte reinicia limpa em vez de estourar a memória do contêiner.
- Mensagens recebidas ficam guardadas sem miniatura (300 por 30 minutos), só para citar e baixar
  mídia; as enviadas ficam em disco, para atender pedido de reenvio.
- Erros soltos dentro do Baileys não derrubam mais a ponte inteira.
- `client.bridgeStats()` devolve RSS, heap e o tamanho de cada cache, para acompanhar ao longo dos
  dias.

## Arquitetura

```text
 Aplicação Java ──► WhatsAppClient (um por número) ──► InteractionQueue (SQLite, virtual thread)
                          │                                    │
                          └──────── BridgeHost (um por JVM) ◄──┘
                                         │ WebSocket local + token
                                         ▼
                              Processo Node.js (ponte)
                              Baileys 7 ── um socket por número ──► WhatsApp
```

A ponte só escuta em `127.0.0.1`, exige um token aleatório gerado a cada início e não recebe
nada de fora da máquina.

## Limitações

- Não há sincronização de histórico, por escolha: só o que chega depois do início do cliente.
- Citar, encaminhar e baixar mídia funciona para mensagens recebidas nos últimos 30 minutos (ou
  enviadas nas últimas 12 horas).
- Editar e apagar para todos só dentro da janela que o WhatsApp permite (cerca de 15 minutos).
- Botões interativos dependem de o WhatsApp do destinatário exibi-los; o estilo `TEXT` sempre
  funciona.

## Migração da 1.x

- Java 25, e Jackson e SLF4J saíram: dados chegam em records tipados (`MessageUpdate`,
  `PresenceUpdate`, `GroupUpdate`, `Account`...) em vez de `JsonNode`.
- Requests viraram records: `SendImageRequest.of(to, Path.of("foto.jpg")).withCaption("...")`.
- Reações, edição e remoção recebem um `MessageRef` (`MessageRef.of(message)`).
- `PacingOptions` e `HumanizationOptions` mudaram de forma (veja acima); o visto padrão agora é
  `ON_REPLY` e a conta fica online por padrão.
- `downloadMedia` grava num `Path` e devolve `MediaFile`, em vez de bytes na memória.
- `displayName` virou `deviceName`; sem ele, o aparelho aparece como "Chrome (Windows)".

## Desenvolvimento

```bash
mvn install                                  # compila a ponte (precisa de npm) e roda os testes
mvn install -Dskip.bridge.build              # usa a ponte já empacotada no repositório
java -jar cli/target/angatu-whatsapp-cli-2.0.0-SNAPSHOT.jar status --session-dir ./sessoes/teste
```

Os testes nunca conectam um número. O `BridgeHostTest` sobe a ponte real (quando há Node na
máquina) e conversa com ela sem tocar no WhatsApp.

## Licença

Apache 2.0 — veja [LICENSE](LICENSE). Feito por [Angatu Sistemas](https://angatusistemas.com.br).
