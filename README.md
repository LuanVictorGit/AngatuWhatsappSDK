# Angatu WhatsApp SDK

SDK Java para integração com WhatsApp, construído sobre o [Baileys](https://github.com/WhiskeySockets/Baileys)
(`baileys@6.7.24`). Permite que uma aplicação Java conecte números de WhatsApp, envie e receba
mensagens, mídia, reações, eventos de grupo e presença — **sem que o desenvolvedor Java precise
escrever, ler ou entender uma linha de Node.js/TypeScript**.

> **Aviso importante.** O Baileys é uma biblioteca **não oficial**, construída por engenharia reversa
> do protocolo do WhatsApp Web — não é a [WhatsApp Business Platform (Cloud API)](https://developers.facebook.com/docs/whatsapp)
> oficial da Meta. Usar este SDK (assim como usar o Baileys diretamente) para automação viola os
> Termos de Serviço do WhatsApp e pode resultar no banimento do número conectado, especialmente em
> uso de alto volume ou com padrões de envio automatizados. Use por sua conta e risco, prefira
> números de teste/descartáveis durante o desenvolvimento, e avalie a API oficial da Meta para
> aplicações comerciais críticas.

## Sumário

- [Visão geral](#visão-geral)
- [Arquitetura](#arquitetura)
- [Estrutura de arquivos](#estrutura-de-arquivos)
- [Requisitos](#requisitos)
- [Instalação](#instalação)
- [Início rápido](#início-rápido)
- [Conectando e QR Code](#conectando-e-qr-code)
- [Enviando mensagens](#enviando-mensagens)
- [Recebendo eventos](#recebendo-eventos)
- [Mídia, contatos e grupos](#mídia-contatos-e-grupos)
- [Múltiplas instâncias](#múltiplas-instâncias)
- [Persistência de sessão e reconexão](#persistência-de-sessão-e-reconexão)
- [Tratamento de erros](#tratamento-de-erros)
- [Configuração](#configuração)
- [Segurança](#segurança)
- [Encerrando o cliente](#encerrando-o-cliente)
- [CLI opcional](#cli-opcional)
- [Exemplos](#exemplos)
- [Referência completa de exemplos (EXAMPLES.md)](EXAMPLES.md)
- [Desenvolvimento](#desenvolvimento)
- [Como testar](#como-testar)
- [Como publicar](#como-publicar)
- [Decisões de arquitetura](#decisões-de-arquitetura)
- [Limitações conhecidas](#limitações-conhecidas)
- [Próximas melhorias recomendadas](#próximas-melhorias-recomendadas)
- [Licença](#licença)

## Visão geral

- **API Java limpa e orientada a objetos** — nenhum tipo do Baileys/Node vaza para a aplicação.
- **Sem instalação manual de Node.js**: o SDK detecta um Node.js compatível já instalado ou baixa
  um automaticamente (verificado por SHA-256) na primeira execução.
- **Processo Bridge Node.js isolado**: cada `WhatsAppClient` sobe seu próprio processo Node local,
  fala com ele por WebSocket (`127.0.0.1`, autenticado por token), e o encerra ao fechar o cliente.
- **Multi-instância**: várias contas de WhatsApp na mesma aplicação, cada uma com seu próprio
  `instanceId` e diretório de sessão, com prevenção de conflito em tempo de execução.
- **Sessão persistente**: a autenticação (estado do Baileys) é salva em disco por instância e
  sobrevive a reinícios — sem novo QR Code a cada deploy.
- **Eventos independentes**: listeners separados por tipo (mensagem, atualização, exclusão,
  reação, estado de conexão, QR Code, grupo, presença, erro) em vez de um único callback genérico.
- **Exceções tipadas** com contexto de diagnóstico (`instanceId`, código de erro do bridge, causa).
- **Reconexão automática** com backoff exponencial limitado, distinguindo logout do usuário,
  perda de conexão, reinício solicitado pelo WhatsApp, falha de autenticação e queda do processo
  bridge.
- **CLI opcional** (`angatu-whatsapp-cli`) para parear um número ou enviar uma mensagem avulsa sem
  escrever código Java.

## Arquitetura

```
┌─────────────────────┐        WebSocket local         ┌──────────────────────┐        ┌──────────┐
│   Aplicação Java     │        (127.0.0.1, token)      │  Bridge Node.js       │        │          │
│                      │  ───────────────────────────▶  │  (processo próprio,   │        │ WhatsApp │
│  WhatsAppClient      │  ◀───────────────────────────  │  spawnado pelo SDK)   │  ────▶ │          │
│  (sdk/)              │   protocolo JSON com IDs        │  Baileys 6.7.24       │        │          │
└─────────────────────┘   únicos, versionado            └──────────────────────┘        └──────────┘
```

O SDK **nunca** porta o Baileys para Java. A regra é rígida: Java fala com um processo Node.js
local via WebSocket, e apenas esse processo (o "bridge", em `bridge/`) importa e usa o Baileys.
Isso significa que:

- Atualizar o Baileys é uma mudança isolada em `bridge/`, sem tocar no código Java.
- Nenhuma regra de negócio da aplicação vive dentro do bridge — ele só traduz eventos/comandos.
- O bridge é pequeno, testável isoladamente (`bridge/src`, `npm run typecheck`) e compilado com
  esbuild num único arquivo (`bridge.mjs`, ~6.5 MB) embutido como recurso dentro do jar do SDK.

### Um processo bridge por cliente

Cada `WhatsAppClient` sobe seu **próprio** processo Node — não há um processo bridge compartilhado
entre todos os clientes da JVM (embora o bridge internamente suporte múltiplas instâncias por
processo). Essa escolha prioriza isolamento de falhas: se o socket do Baileys de um número trava
ou o processo bridge morre, apenas aquele `WhatsAppClient` é afetado, nunca os outros. O custo é um
processo Node extra por número conectado — para a maioria dos casos de uso (dezenas de números,
não milhares) isso é uma troca razoável. Veja [Próximas melhorias](#próximas-melhorias-recomendadas)
para um possível modo opcional de processo compartilhado.

### Protocolo Java ↔ Bridge

JSON sobre WebSocket, com um envelope por mensagem (`type`, `id`, e campos específicos do tipo):
`handshake` / `handshake_ack`, `heartbeat` / `heartbeat_ack`, `request` / `response`, `event`,
`error`. Cada requisição carrega um ID único gerado pelo Java e correlacionado com sua resposta;
o handshake inclui a versão do protocolo (`ProtocolVersion.CURRENT`) e é rejeitado se o major
version não bater com o do bridge. Um heartbeat de aplicação (não o keepalive nativo do
WebSocket) detecta conexões mortas que o TCP ainda não percebeu.

## Estrutura de arquivos

```
AngatuWhatsAppSDK/
├── pom.xml                            # POM agregador (multi-módulo Maven)
├── LICENSE                            # Apache License 2.0
├── sdk/                                # com.angatusistemas:angatu-whatsapp — o artefato publicado
│   ├── src/main/java/com/angatusistemas/whatsapp/
│   │   ├── WhatsAppClient.java         # Fachada pública principal
│   │   ├── ConnectionState.java        # Enum de estado de conexão
│   │   ├── DisconnectCause.java        # Enum de causa de desconexão
│   │   ├── model/                      # Message, Contact, Group, MediaContent, ...
│   │   ├── listener/                   # 9 interfaces funcionais de evento
│   │   ├── request/                    # Builders para envio de mídia/localização/contato/reação
│   │   ├── exception/                  # Hierarquia de exceções tipadas
│   │   ├── protocol/                   # Envelope, codec JSON, versionamento
│   │   ├── transport/                  # BridgeConnection (cliente WebSocket)
│   │   ├── runtime/                    # Localização/download do Node, processo do bridge
│   │   └── internal/                   # InstanceRegistry, QrCodeRenderer (uso interno do SDK)
│   ├── src/main/resources/bridge/      # bridge.mjs compilado é copiado para cá no build
│   └── src/test/java/...               # Testes unitários e de integração (mock bridge)
├── bridge/                             # Bridge Node.js/TypeScript — nunca publicado, só embutido
│   ├── src/
│   │   ├── adapter/                    # BaileysAdapter, MessageMapper, InstanceManager
│   │   ├── protocol/                   # Envelope/erros espelhando o lado Java
│   │   └── server/                     # Servidor WebSocket local, autenticação por token
│   ├── build.mjs                       # Bundle esbuild → dist/bridge.mjs
│   └── package.json
├── cli/                                # com.angatusistemas:angatu-whatsapp-cli — opcional
│   └── src/main/java/com/angatusistemas/whatsapp/cli/
├── examples/
│   ├── simple-bot/                     # Uma instância, QR em PNG, eco de mensagens recebidas
│   └── multi-instance/                 # Várias instâncias isoladas na mesma aplicação
```

## Requisitos

- **Java 17+** para consumir o SDK.
- **Uma das opções a seguir** para o runtime Node.js usado pelo bridge:
  - Node.js **20+** já instalado no sistema (detectado automaticamente), **ou**
  - Nenhum — o SDK baixa um Node.js gerenciado (verificado por SHA-256 contra o `SHASUMS256.txt`
    oficial do nodejs.org) na primeira execução e o mantém em cache local.
- **Node.js + npm no ambiente de build** (não no runtime dos consumidores) apenas se você for
  **compilar o SDK a partir do código-fonte** — o bridge é compilado durante `mvn install` via um
  profile Maven que roda `npm install && npm run build`. Consumidores que só adicionam a
  dependência Maven não precisam de Node.js instalado para *compilar* sua aplicação — apenas em
  runtime, para o bridge já embutido rodar (via Node do sistema ou baixado automaticamente).
- Windows, Linux ou macOS (x64/arm64).
- Acesso à internet na primeira conexão de cada número (para o handshake com os servidores do
  WhatsApp) e, se nenhum Node.js compatível for encontrado localmente, para baixá-lo de
  `nodejs.org` uma única vez.

## Instalação

O artefato ainda não foi publicado no Maven Central (versão atual: `1.0.0-SNAPSHOT`). Duas formas
de consumir o SDK:

### Opção 1 — Maven Central (após uma release)

```xml
<dependency>
    <groupId>com.angatusistemas</groupId>
    <artifactId>angatu-whatsapp</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Opção 2 — JitPack (direto do GitHub, sem precisar de uma release no Central)

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.LuanVictorGit</groupId>
    <artifactId>angatu-whatsapp</artifactId>
    <version><!-- tag, branch ou commit hash do GitHub --></version>
</dependency>
```

O JitPack builda o módulo `sdk` (com o bridge embutido) diretamente a partir do repositório
público no GitHub — funciona a partir de qualquer tag, branch ou commit, sem exigir conta no
Sonatype/Central Portal.

### Opção 3 — instalar localmente a partir do código-fonte

```bash
git clone https://github.com/LuanVictorGit/AngatuWhatsappSDK.git
cd angatu-whatsapp
mvn clean install
```

Isso publica `com.angatusistemas:angatu-whatsapp:1.0.0-SNAPSHOT` no seu repositório Maven local
(`~/.m2`), pronto para ser usado como dependência em outro projeto na mesma máquina.

## Início rápido

```java
import com.angatusistemas.whatsapp.WhatsAppClient;

import java.util.concurrent.CountDownLatch;

public class QuickStart {
    public static void main(String[] args) throws Exception {
        WhatsAppClient client = WhatsAppClient.builder()
                .instanceId("meu-bot")
                .sessionDirectory("./whatsapp-session")
                .build();

        client.addQrCodeListener(qr ->
                System.out.println("Escaneie no WhatsApp: " + qr));

        client.addConnectionStateListener((state, cause, detail) ->
                System.out.println("Estado: " + state));

        client.addMessageListener(message -> {
            if (!message.isFromMe()) {
                client.sendText(message.chatId(), "Recebi: " + message.text());
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(client::close));

        client.connect().join(); // confirma que o bridge aceitou iniciar a conexão

        new CountDownLatch(1).await(); // mantém a JVM viva - veja "Encerrando o cliente"
    }
}
```

`client.connect()` retorna assim que o bridge confirma que iniciou a tentativa de conexão — **não**
espera o WhatsApp autenticar. O progresso real (`QR_PENDING`, `CONNECTED`, etc.) chega pelos
listeners, de forma assíncrona. Veja [`examples/simple-bot`](examples/simple-bot) para uma versão
completa e executável deste exemplo.

## Conectando e QR Code

```java
WhatsAppClient client = WhatsAppClient.builder()
        .instanceId("vendas")
        .sessionDirectory("./sessions/vendas")
        .displayName("Vendas - Angatu")   // nome exibido em "Aparelhos conectados"
        .build();

client.addQrCodeListener(qr -> {
    // qr é o conteúdo bruto do QR Code (string). Duas formas de exibi-lo:
    byte[] png = client.getQrCodePng();       // PNG 300x300 pronto para salvar/servir
    byte[] pngGrande = client.getQrCodePng(500);
});

client.connect();
```

- `getCurrentQrCode()` devolve o conteúdo bruto atual (ou `null` se não há QR pendente).
- `getQrCodePng()` / `getQrCodePng(int tamanho)` renderizam o QR atual como PNG localmente (via
  ZXing) — nenhuma chamada a serviço externo de terceiros.
- O QR Code é rotativo: o WhatsApp o expira e o bridge emite um novo periodicamente enquanto o
  estado permanece `QR_PENDING`. O listener é chamado a cada novo QR.

### Pareamento por código (alternativa ao QR Code)

```java
client.connect().join();
String codigo = client.requestPairingCode("5511999999999").get(30, TimeUnit.SECONDS);
System.out.println("Digite este código no WhatsApp: " + codigo);
```

> O fluxo de QR Code foi verificado de ponta a ponta neste projeto (conexão real, autenticação e
> recebimento de eventos). O fluxo de código de pareamento foi validado apenas até a obtenção do
> código pelo bridge/Baileys — teste-o no seu ambiente antes de depender dele em produção.

## Enviando mensagens

Todos os métodos de envio retornam `CompletableFuture<String>` com o `messageId` gerado (ou
`CompletableFuture<Void>` para `sendReaction`), e nunca bloqueiam a thread chamadora.

```java
// Texto simples
client.sendText("5511999999999@s.whatsapp.net", "Olá!");

// Texto respondendo a outra mensagem
client.sendText(chatId, "Respondendo...", quotedMessageId);

// Imagem
client.sendImage(SendImageRequest.builder()
        .to(chatId)
        .filePath("/caminho/para/imagem.jpg")
        .caption("Legenda opcional")
        .build());

// Vídeo (ou GIF via gifPlayback)
client.sendVideo(SendVideoRequest.builder()
        .to(chatId).filePath("/caminho/video.mp4").gifPlayback(false).build());

// Áudio (ptt=true para nota de voz)
client.sendAudio(SendAudioRequest.builder()
        .to(chatId).filePath("/caminho/audio.ogg").ptt(true).build());

// Documento
client.sendDocument(SendDocumentRequest.builder()
        .to(chatId).filePath("/caminho/arquivo.pdf")
        .fileName("relatorio.pdf").mimetype("application/pdf").build());

// Sticker
client.sendSticker(SendStickerRequest.builder().to(chatId).filePath("/caminho/figurinha.webp").build());

// Localização
client.sendLocation(SendLocationRequest.builder(-23.5505, -46.6333)
        .to(chatId).name("Sede Angatu").address("São Paulo, SP").build());

// Cartão de contato
client.sendContact(SendContactRequest.builder()
        .to(chatId).displayName("Suporte").phoneNumber("5511988888888").build());

// Reação (emoji=null remove a reação existente)
client.sendReaction(SendReactionRequest.builder()
        .chatId(chatId).messageId(messageId).emoji("👍").fromMe(false).build());
```

`filePath` é lido pelo processo bridge local (mesma máquina onde a aplicação Java roda), não pela
aplicação Java diretamente — use caminhos absolutos ou relativos ao diretório de trabalho do
processo Java, que é o mesmo processo que inicia o bridge.

Todos os builders (exceto `SendLocationRequest`, cujas coordenadas são obrigatórias no próprio
`builder(lat, lon)`) seguem o mesmo padrão: `to`/`chatId` obrigatório, demais campos opcionais.

## Recebendo eventos

Nove interfaces funcionais, registradas independentemente — assine só o que precisar:

| Listener | Método | Quando dispara |
|---|---|---|
| `MessageListener` | `onMessageReceived(Message message)` | Nova mensagem (própria ou de terceiros) |
| `MessageUpdatedListener` | `onMessageUpdated(String chatId, String messageId, JsonNode updates)` | Edição/atualização de mensagem existente |
| `MessageDeletedListener` | `onMessageDeleted(String chatId, String messageId)` | Mensagem apagada |

Do lado ativo, `editMessage` e `deleteMessage` fazem o mesmo pela sua conta.
Os dois só valem dentro da janela que o WhatsApp concede depois da entrega
(cerca de 15 minutos, decididos pelo servidor e sem limite publicado): não há
como consultar o prazo, então a recusa do servidor é a resposta.
| `ReactionListener` | `onReactionReceived(String chatId, String messageId, String senderId, String emoji, boolean removed)` | Reação adicionada ou removida |
| `ConnectionStateListener` | `onConnectionStateChanged(ConnectionState state, DisconnectCause cause, String detail)` | Mudança de estado da conexão |
| `QrCodeListener` | `onQrCodeReceived(String qrCode)` | Novo QR Code disponível |
| `GroupUpdateListener` | `onGroupUpdated(String groupJid, JsonNode updates)` | Metadados de grupo alterados |
| `PresenceListener` | `onPresenceUpdated(String chatId, JsonNode presences)` | Presença (online/digitando) alterada |
| `ErrorListener` | `onError(WhatsAppException error)` | Erro assíncrono reportado pelo SDK/bridge |

```java
client.addMessageListener(message -> {
    System.out.println(message.senderId() + ": " + message.text());
});

client.addConnectionStateListener((state, cause, detail) -> {
    if (state == ConnectionState.FAILED) {
        // maxReconnectAttempts esgotado - decida se recria o cliente ou alerta um humano
    }
});

client.addErrorListener(error -> log.error("erro do SDK: {}", error.getMessage(), error));
```

Cada listener roda numa thread interna do SDK; exceções não tratadas lançadas por um listener são
capturadas e logadas (nunca derrubam a thread de despacho nem propagam para outros listeners).
Múltiplos listeners do mesmo tipo podem ser registrados — todos são chamados, na ordem de registro.

`Message` é um record com os campos `messageId`, `instanceId`, `chatId`, `senderId`, `timestamp`
(e `timestampAsInstant()`), `text`, `type` (`MessageType`), `isGroup`, `isFromMe`, `pushName`,
`quotedMessage` e `rawData` (o `JsonNode` bruto do Baileys, para casos não cobertos pela
abstração).

## Mídia, contatos e grupos

```java
// Baixar mídia de uma mensagem recebida
MediaContent media = client.downloadMedia(chatId, messageId).get();
Files.write(Path.of("recebido.jpg"), media.data()); // media.mimetype() também disponível

// Só a foto de perfil — uma consulta ao WhatsApp
JsonNode foto = client.getProfilePicture("5511999999999@s.whatsapp.net").get();
String url = foto.path("profilePictureUrl").asText(null);   // null quando não há foto

// Contato completo — TRÊS consultas (ver o aviso abaixo)
Contact contato = client.getContact("5511999999999@s.whatsapp.net").get();

// Grupos
Group grupo = client.getGroupMetadata(groupJid).get();
List<Group> meusGrupos = client.listGroups().get();
Group novo = client.createGroup("Time de Vendas", List.of(jid1, jid2)).get();

client.updateGroupParticipants(groupJid, List.of(jid1),
        WhatsAppClient.GroupParticipantAction.PROMOTE);   // ADD, REMOVE, PROMOTE, DEMOTE
client.updateGroupSubject(groupJid, "Novo nome do grupo");
client.updateGroupDescription(groupJid, "Nova descrição");
client.leaveGroup(groupJid);
String conviteUrl = client.getGroupInviteCode(groupJid).get();
```

> ### ⚠️ `getContact` faz três consultas — prefira `getProfilePicture`
>
> `getContact(jid)` dispara `onWhatsApp` (o número existe?), `profilePictureUrl`
> (a foto) e `fetchStatus` (o **recado pessoal** do contato). Numa tela que
> mostra o avatar de uma lista de contatos isso vira uma varredura da agenda:
> 200 contatos = 600 consultas em rajada, sondando existência de número e lendo
> o recado pessoal de gente que a aplicação nem exibe.
>
> Isso é leitura em massa de informação de terceiro, e o WhatsApp **suspende
> contas** por esse comportamento — foi o que aconteceu com uma aplicação que
> usa esta SDK. Se o objetivo é só exibir um avatar, use
> `getProfilePicture(jid)`: uma consulta, só o dado que será mostrado. E guarde
> o resultado: a foto de perfil não muda de minuto em minuto.

## Múltiplas instâncias

Cada `WhatsAppClient` é uma conta de WhatsApp independente. `instanceId` e `sessionDirectory`
precisam ser únicos por instância **dentro da mesma JVM** — tentar reusar qualquer um dos dois
lança `InstanceException` imediatamente na construção, antes mesmo do bridge ser iniciado:

```java
WhatsAppClient vendas = WhatsAppClient.builder()
        .instanceId("vendas").sessionDirectory("./sessions/vendas").build();

WhatsAppClient suporte = WhatsAppClient.builder()
        .instanceId("suporte").sessionDirectory("./sessions/suporte").build();

vendas.connect();
suporte.connect();
```

Como cada instância sobe seu próprio processo bridge (veja [Arquitetura](#arquitetura)), uma
queda no processo de `vendas` nunca afeta `suporte`. Veja
[`examples/multi-instance`](examples/multi-instance) para um exemplo completo com N instâncias
configuráveis por argumento de linha de comando.

> A prevenção de conflito de `instanceId`/`sessionDirectory` é por JVM, não entre processos
> diferentes — veja [Limitações conhecidas](#limitações-conhecidas).

## Persistência de sessão e reconexão

`sessionDirectory` é onde o Baileys persiste as credenciais de autenticação (via
`useMultiFileAuthState`) — reiniciar a aplicação apontando para o mesmo diretório retoma a sessão
sem exigir um novo QR Code, contanto que o WhatsApp não tenha invalidado a sessão nesse meio
tempo. **Nunca commite esse diretório em controle de versão** (o `.gitignore` do projeto já
exclui `sessions/` e `**/sessions/` por padrão).

### Uma conta, um socket

O adapter garante **no máximo um socket Baileys por instância**, e isso não é
detalhe de implementação: dois sockets vivos na mesma conta fazem o servidor
derrubar um com `connectionReplaced`, o que dispara outra reconexão, que abre
outro socket — um ciclo em que a conta aparece para o WhatsApp como vários
clientes web surgindo e sumindo.

Três garantias sustentam isso:

- **abertura serializada.** `openSocket()` espera três coisas antes de criar o
  socket (limpeza de sessão invalidada, leitura das credenciais e a consulta da
  versão do WhatsApp Web, que sozinha leva até 9s). Duas chamadas concorrentes
  atravessavam essa janela juntas; agora compartilham a mesma promessa, e
  `connect()` é no-op quando já existe socket ativo;
- **listeners com número de geração.** Cada assinatura sabe se desfazer e
  carrega a geração do socket em que nasceu. Antes, cada reconexão somava um
  jogo novo de listeners e os antigos continuavam vivos — a mesma mensagem era
  entregue N vezes, uma por reconexão acumulada;
- **credenciais gravadas pelo escritor do próprio socket.** O listener de
  `creds.update` usava um campo compartilhado que a troca de socket já tinha
  reapontado, gravando as credenciais de um pela função do outro. Aparece depois
  como "pediu QR do nada".

### Acesso mínimo: o que este cliente não pede ao WhatsApp

O socket é configurado para ler só o que a aplicação usa. Não é otimização — é
o que separa um cliente de mensagens de um coletor de dados, e o WhatsApp
suspende contas pela diferença:

| opção | valor | efeito |
|---|---|---|
| `syncFullHistory` / `shouldSyncHistoryMessage` | `false` / `() => false` | nenhum histórico antigo é importado do celular |
| `shouldIgnoreJid` | ignora `@broadcast` e `@newsletter` | o "Recado" de todos os contatos é descartado **antes de ser decifrado** |
| `emitOwnEvents` | `false` | a mensagem que a aplicação envia não volta como evento para ser processada de novo |
| `generateHighQualityLinkPreview` | `false` | não baixa a página de destino dos links |

O adapter também **não assina `contacts.upsert`/`contacts.update`**: esse evento
entrega a lista de contatos inteira da conta, e guardá-la seria acumular dado
pessoal de terceiro sem uso. O `LidDirectory` aprende o par LID/telefone do
próprio stanza da conversa (`learnLids`), que é tudo de que ele precisa.

`messages.upsert` processa `notify` **e** `append`. `append` parece histórico e
não é: é a mensagem que chegou enquanto o cliente estava offline. Descartá-la
perderia respostas recebidas durante uma queda de conexão.

A reconexão acontece em duas camadas:

1. **Dentro do bridge**: o Baileys já trata sozinho a maioria das quedas transitórias
   (`CONNECTION_LOST`, `WHATSAPP_RESTART`) sem intervenção do Java.
2. **Entre Java e o bridge**: se a conexão WebSocket cair mas o processo bridge continuar vivo, o
   SDK reconecta só o WebSocket e resincroniza o estado (rápido). Se o processo bridge morrer
   (`BRIDGE_CRASH`), o SDK recria o processo inteiro, reabre a conexão e retoma a sessão do disco
   — sem novo QR Code, pois o estado de autenticação já está persistido.

O backoff é exponencial com jitter, limitado por `maxReconnectDelay` e por `maxReconnectAttempts`
(padrão: 10 tentativas). Ao esgotar as tentativas, a instância vai para `ConnectionState.FAILED`
e para de tentar sozinha — nesse ponto, cabe à aplicação decidir (alertar, recriar o cliente, etc.)
via `ErrorListener`/`ConnectionStateListener`.

`DisconnectCause` distingue: `USER_LOGOUT` (logout explícito — precisa de novo QR),
`CONNECTION_LOST` (falha transitória de rede), `WHATSAPP_RESTART` (o próprio WhatsApp pediu
reinício), `AUTH_FAILURE` (credenciais rejeitadas — precisa de novo QR), `BRIDGE_CRASH` (processo
Node morreu inesperadamente) e `UNKNOWN`.

## Tratamento de erros

Toda falha do SDK é uma subclasse não verificada (`RuntimeException`) de `WhatsAppException`, com
`instanceId()`, `errorCode()` (`BridgeErrorCode`) e `details()` quando disponíveis:

```
WhatsAppException
├── ConnectionException        (NOT_CONNECTED, CONNECTION_FAILED)
├── AuthenticationException    (AUTH_FAILED)
├── TimeoutException           (TIMEOUT)
├── MessageSendException       (SEND_FAILED, MEDIA_NOT_FOUND)
├── InstanceException          (INSTANCE_NOT_FOUND, INSTANCE_ALREADY_EXISTS)
├── UnsupportedFeatureException(UNSUPPORTED_FEATURE)
└── BridgeException            (INVALID_TOKEN, PROTOCOL_VERSION_MISMATCH, INVALID_MESSAGE,
                                 UNKNOWN_ACTION, INVALID_PAYLOAD, INTERNAL_ERROR, UNKNOWN)
```

```java
client.sendText(chatId, "oi").exceptionally(ex -> {
    if (ex.getCause() instanceof MessageSendException mse) {
        log.warn("falha ao enviar para {}: {}", mse.instanceId().orElse("?"), mse.getMessage());
    }
    return null;
});
```

Erros assíncronos que não são resposta direta de uma chamada (ex.: falha ao processar um evento
recebido do bridge) chegam pelo `ErrorListener`. Nada é engolido silenciosamente — toda exceção
carrega mensagem, causa original (quando aplicável) e o máximo de contexto disponível no momento
da falha.

## Configuração

Todas as opções do `WhatsAppClient.builder()`:

| Método | Obrigatório | Padrão | Descrição |
|---|---|---|---|
| `instanceId(String)` | Sim | — | Identificador único da instância nesta JVM |
| `sessionDirectory(String\|Path)` | Sim | — | Diretório de persistência da sessão (único nesta JVM) |
| `displayName(String)` | Não | padrão do bridge | Nome exibido em "Aparelhos conectados" |
| `markOnlineOnConnect(boolean)` | Não | `false` | Marcar como "online" assim que conectar |
| `minReconnectDelay(Duration)` | Não | 1s | Menor atraso entre tentativas de reconexão |
| `maxReconnectDelay(Duration)` | Não | 30s | Maior atraso entre tentativas de reconexão |
| `maxReconnectAttempts(int)` | Não | 10 | Tentativas consecutivas antes de ir para `FAILED` |
| `nodeVersion(String)` | Não | versão LTS atual pinada pelo SDK | Versão do Node a baixar se necessário |
| `bridgeHost(String)` | Não | `127.0.0.1` | **Avançado** — veja [Segurança](#segurança) |
| `bridgePort(int)` | Não | porta efêmera do SO | Fixar uma porta local em vez de uma aleatória |

Variáveis de ambiente/propriedades de sistema reconhecidas pelo SDK:

| Nome | Tipo | Efeito |
|---|---|---|
| `ANGATU_WHATSAPP_HOME` | Variável de ambiente | Sobrescreve o diretório de cache (Node baixado, bridge instalado) |
| `ANGATU_WHATSAPP_NODE_PATH` | Variável de ambiente | Força um executável Node.js específico (erro se inválido) |
| `angatu.whatsapp.node.path` | Propriedade de sistema (`-D`) | Mesmo efeito que `ANGATU_WHATSAPP_NODE_PATH`, com prioridade maior |

Sem essas variáveis, o cache fica em `%LOCALAPPDATA%\angatu-whatsapp-sdk` (Windows),
`$XDG_CACHE_HOME/angatu-whatsapp-sdk` ou `~/.angatu-whatsapp-sdk` (Linux/macOS).

## Segurança

- O servidor WebSocket do bridge escuta apenas em `127.0.0.1` por padrão — não é acessível pela
  rede.
- Cada processo bridge recebe um token aleatório de 32 bytes (`SecureRandom`, base64url) gerado
  pelo Java e nunca escrito em log; toda requisição/handshake é validada contra esse token.
- O bridge valida a forma de toda mensagem recebida antes de processá-la; nenhuma mensagem aciona
  execução arbitrária de comandos do sistema.
- `bridgeHost(String)` existe para cenários avançados (ex.: bridge rodando em outro container na
  mesma rede interna) — **não exponha essa porta além de loopback sem entender as implicações**:
  qualquer processo capaz de alcançar o host/porta e de obter o token poderia enviar comandos
  como se fosse a aplicação Java legítima.
- Credenciais do WhatsApp (estado do Baileys) ficam apenas no `sessionDirectory`, em disco local —
  o SDK nunca as envia a nenhum serviço externo.

## Encerrando o cliente

```java
client.close(); // ou: try (WhatsAppClient client = ...) { ... }
```

`close()` é síncrono e idempotente: pede ao bridge para encerrar de forma organizada, espera a
confirmação, força o encerramento do processo se necessário, e libera o `instanceId`/
`sessionDirectory` para reuso. **Não há um shutdown hook da JVM registrado automaticamente** — se
o processo Java for encerrado sem chamar `close()`, o processo bridge pode ficar órfão. Registre
seu próprio hook, como em todos os exemplos deste projeto:

```java
Runtime.getRuntime().addShutdownHook(new Thread(client::close));
```

## CLI opcional

Um executável de linha de comando fino sobre a mesma API pública, útil para parear um número ou
disparar uma mensagem avulsa sem escrever Java:

```bash
mvn -pl cli -am package
java -jar cli/target/angatu-whatsapp-cli-1.0.0-SNAPSHOT.jar connect \
    --instance-id meu-bot --session-dir ./sessions/meu-bot
```

Comandos disponíveis:

| Comando | Uso |
|---|---|
| `connect` | `--instance-id <id> --session-dir <dir> [--display-name <nome>] [--pairing-phone <numero>]` — conecta, exibe o QR Code (ASCII) ou código de pareamento, e permanece em execução até Ctrl+C |
| `status` | `--instance-id <id> --session-dir <dir>` — conecta brevemente e imprime o estado atual |
| `send` | `--instance-id <id> --session-dir <dir> --to <jid> --text <mensagem>` — envia uma mensagem e encerra |
| `logout` | `--instance-id <id> --session-dir <dir>` — invalida a sessão |

Cada comando inicia seu **próprio** processo bridge para o `--session-dir` informado — não execute
dois comandos simultaneamente contra o mesmo diretório de sessão (disputariam o mesmo estado de
autenticação em disco). O CLI é um consumidor comum do SDK, como qualquer aplicação Java: não é
uma dependência do módulo `sdk`, e nunca é necessário para usar a biblioteca.

## Exemplos

Depois de um `mvn clean install` na raiz (necessário uma vez, para o módulo `sdk` ficar disponível
no repositório Maven local):

```bash
# Bot de eco de uma instância só, QR salvo como PNG
mvn -pl examples/simple-bot exec:java

# Várias instâncias na mesma aplicação (padrão: instance-a, instance-b)
mvn -pl examples/multi-instance exec:java -Dexec.args="loja-1 loja-2"
```

(Sem `-am` aqui: encadear `-am` com um goal avulso como `exec:java`, em vez de uma fase do ciclo
de vida, faz o Maven tentar rodá-lo em todo o subconjunto do reactor incluído por `-am` — inclusive
no POM agregador, que não tem esse plugin configurado. Rodar `exec:java` sozinho, contra um único
módulo, evita o problema.)

Para uma referência exaustiva — todo método público, todo listener, todo builder de requisição,
todo campo de cada modelo de dados, com um exemplo de código para cada um — veja
[**EXAMPLES.md**](EXAMPLES.md). Esse documento foi escrito para servir tanto como guia para
desenvolvedores quanto como referência precisa para outras IAs gerarem código correto contra
esta API.

## Desenvolvimento

```bash
git clone https://github.com/LuanVictorGit/AngatuWhatsappSDK.git
cd angatu-whatsapp

# Build completo (compila o bridge via npm install && npm run build, depois o Java)
mvn clean install
```

Requer Node.js/npm no ambiente de build (não no runtime dos consumidores — veja
[Requisitos](#requisitos)). Para iterar só no bridge:

```bash
cd bridge
npm install
npm run typecheck   # tsc --noEmit
npm run dev          # roda direto do TypeScript via tsx, sem empacotar
npm run build         # gera dist/bridge.mjs (o que o build Maven também faz)
```

> Depois de qualquer mudança manual no bridge (fora do `mvn clean install` completo), rode
> `npm run build` **e então** `mvn -pl sdk compile -Dskip.bridge.build=true` — o segundo passo
> copia o `bridge.mjs` recém-gerado para `sdk/target/classes/bridge/`, que é de onde o SDK
> realmente o lê em tempo de execução/teste. `npm run build` sozinho não atualiza essa cópia.

`-Dskip.bridge.build=true` pula os profiles `build-bridge-windows`/`build-bridge-unix` (que rodam
`npm install`/`npm run build`) e reaproveita o que já está em `sdk/src/main/resources/bridge/` —
útil para iterar rápido no código Java sem esperar o build do bridge a cada vez.

## Como testar

```bash
mvn test                       # todos os módulos
mvn -pl sdk test                # só o sdk
mvn -pl cli test                # só o cli
```

O módulo `sdk` cobre, sem depender de uma conta real de WhatsApp:

- `ProtocolCodecTest` — round-trip de codificação/decodificação de cada tipo de mensagem do
  protocolo, e rejeição de entrada malformada.
- `BridgeExceptionTranslatorTest` — todo `BridgeErrorCode` mapeado para o tipo de exceção correto.
- `NodeVersionTest` — parsing de versão do Node (`node --version`, texto de banner, entradas
  inválidas).
- `InstanceRegistryTest` — detecção de conflito de `instanceId`/`sessionDirectory`.
- `MessageTest` / `ContactTest` / `GroupTest` — mapeamento de JSON do bridge para os modelos
  públicos, incluindo os nomes de campo nativos do Baileys que `Group` deliberadamente preserva
  (`desc`, `creation`).
- `BridgeConnectionTest` — handshake, heartbeat, timeout, requisição/resposta, eventos e queda de
  conexão, contra um **mock do bridge** (`MockBridgeServer`, WebSocket RFC 6455 implementado à mão
  sobre socket bloqueante — veja [Decisões de arquitetura](#decisões-de-arquitetura) para o porquê
  de não usar a própria classe de servidor da biblioteca WebSocket aqui).

O módulo `cli` cobre o parser de argumentos e o renderizador de QR Code em ASCII (incluindo, depois
de um bug real encontrado durante o desenvolvimento, que a saída é estritamente ASCII — veja a
seção de decisões de arquitetura sobre `stdout.encoding` no Windows).

Testes de integração real Java↔Bridge↔WhatsApp (com um número de telefone de verdade) não fazem
parte da suíte automatizada — foram verificados manualmente durante o desenvolvimento deste
projeto (conexão, QR Code, autenticação, troca de mensagens, causas de desconexão) e podem ser
repetidos a qualquer momento com o CLI (`connect`) ou os exemplos.

## Como publicar

### Maven Central

1. Conta no [Central Portal](https://central.sonatype.com/) com o `groupId` `com.angatusistemas`
   verificado, e uma chave GPG para assinar os artefatos.
2. Configure `~/.m2/settings.xml` com um `<server>` de id `central` contendo o token do Central
   Portal, e sua chave GPG disponível para o `maven-gpg-plugin`.
3. Atualize `<version>` no `pom.xml` raiz (e nos módulos) removendo o sufixo `-SNAPSHOT`.
4. Rode:

   ```bash
   mvn clean deploy -Prelease
   ```

   O profile `release` (inerte em builds normais) anexa sources e javadoc, assina tudo com GPG e
   publica via `central-publishing-maven-plugin`.

### JitPack (alternativa sem conta no Central)

Nenhum passo de publicação necessário além de dar push de uma tag no GitHub:

```bash
git tag v1.0.0
git push origin v1.0.0
```

O JitPack builda sob demanda na primeira vez que alguém declara a dependência (veja
[Instalação](#instalação)).

## Decisões de arquitetura

Registradas aqui porque cada uma foi uma mudança deliberada em relação ao plano inicial mais
óbvio, descoberta durante o desenvolvimento real deste projeto — não escolhas arbitrárias.

**`HttpURLConnection` em vez de `java.net.http.HttpClient` para baixar o Node.js.**
`NodeRuntimeDownloader` usa a API HTTP clássica, não a `HttpClient` moderna do JDK. Motivo:
construir um `HttpClient`/`WebSocket` builtin abre um `java.nio.channels.Selector` internamente, e
em determinados ambientes Windows restritos/sandboxed isso falha com
`IOException: Unable to establish loopback connection` (mecanismo interno de wakeup do
`Selector` tentando abrir um socket de domínio Unix local, que o ambiente rejeita) — reproduzido e
confirmado durante o desenvolvimento, inclusive forçando explicitamente o
`WindowsSelectorProvider` legado. `HttpURLConnection` usa I/O bloqueante clássico e não tem essa
dependência.

**`Java-WebSocket` (`org.java-websocket`) em vez do `java.net.http.WebSocket` builtin para o
cliente real Java↔Bridge.** Mesma causa raiz do item anterior — o cliente WebSocket builtin do JDK
também depende de `Selector.open()`. Como esse cliente carrega todo o tráfego Java↔Bridge, esse
risco não valia a pena para um ambiente que pode ser tão restrito quanto o observado durante o
desenvolvimento. `Java-WebSocket` usa sockets bloqueantes clássicos e funciona nesses ambientes.

**Mock do bridge em testes implementado à mão sobre `ServerSocket`, não sobre
`org.java_websocket.server.WebSocketServer`.** A mesma limitação de `Selector` também afeta a
classe de *servidor* do Java-WebSocket (ela também chama `Selector.open()` internamente). Como o
bridge real sempre atua como servidor nessa conexão, `MockBridgeServer` implementa o suficiente do
RFC 6455 (handshake HTTP Upgrade, frames de texto/close/ping-pong, mascaramento) manualmente sobre
`java.net.ServerSocket` bloqueante para os testes de `BridgeConnectionTest` rodarem de forma
determinística nesses mesmos ambientes.

**Um processo bridge por `WhatsAppClient`, não um processo compartilhado por JVM.** Veja
[Arquitetura](#arquitetura) — troca deliberada de mais processos Node por isolamento de falhas
entre instâncias.

**CLI renderiza o QR Code em blocos ASCII (`#`/espaço), não em caracteres Unicode de bloco
(`█`).** Descoberto durante a verificação manual do comando `connect`: no Windows, a propriedade
`stdout.encoding` da JVM segue o *codepage* nativo do console (tipicamente `Cp1252`) mesmo quando
a saída está redirecionada (não é um console interativo de verdade) — e `Cp1252` não representa
`U+2588`, então cada módulo do QR virava um `?` literal. ASCII puro tem representação idêntica e
sem ambiguidade em qualquer codepage/terminal, então a saída do QR não depende de nenhuma
configuração de codificação da máquina do usuário.

## Limitações conhecidas

- **Baileys não é oficial** (veja o aviso no topo deste README) — risco de banimento em uso
  automatizado é inerente à abordagem, não a este SDK especificamente.
- **Um processo Node.js por instância conectada** — custo de recursos que cresce linearmente com
  o número de contas de WhatsApp simultâneas na mesma aplicação. Não recomendado, no desenho
  atual, para centenas/milhares de instâncias na mesma JVM.
- **Prevenção de conflito de `instanceId`/`sessionDirectory` é por JVM**, não entre processos
  diferentes na mesma máquina ou em máquinas diferentes — rodar dois processos apontando para o
  mesmo `sessionDirectory` (ex.: dois comandos do CLI, ou uma instância do CLI e uma aplicação
  Java própria) não é impedido pelo SDK e corrompe o estado de autenticação do Baileys em disco,
  já que ambos tentariam abrir o mesmo `useMultiFileAuthState` concorrentemente.
- **Download do Node.js verificado apenas por SHA-256** obtido do próprio `nodejs.org` no momento
  do download (mesmo modelo de confiança de ferramentas como nvm/volta/fnm) — não há verificação
  de assinatura GPG independente.
- **Extração do tarball do Node em Linux/macOS depende do binário `tar` do sistema** estar no
  `PATH` (Windows usa o executável standalone `node.exe`, sem extração).
- **Sem sincronização de histórico de conversas** — o SDK expõe eventos em tempo real e consultas
  pontuais (contato, grupo, mídia de uma mensagem específica), não uma sincronização completa do
  histórico de chats como um cliente WhatsApp Web faz ao conectar.
- **Sem limitação de taxa de envio embutida** — enviar mensagens em volume alto/rápido é
  responsabilidade da aplicação; o WhatsApp pode marcar como spam ou banir por padrões agressivos.
- **`close()` gracioso depende do WebSocket ainda estar alcançável.** No Windows,
  `Process.destroy()` não aciona um handler de sinal no processo filho (ao contrário de Unix) —
  por isso o encerramento organizado é feito pedindo `shutdown` pelo próprio WebSocket antes de
  qualquer tentativa de matar o processo; se a conexão já estiver morta, o encerramento cai direto
  para o término forçado do processo.
- **Fluxo de código de pareamento menos testado que o de QR Code** em verificação manual real
  (veja [Conectando e QR Code](#conectando-e-qr-code)).

## Próximas melhorias recomendadas

- Modo opcional de processo bridge compartilhado entre várias instâncias na mesma JVM, para casos
  de uso com um número grande de contas simultâneas (o bridge já suporta múltiplas instâncias por
  processo — falta expor essa opção no lado Java).
- Sincronização/consulta de histórico de conversas.
- Utilitário opcional de limitação de taxa de envio (rate limiting) embutido no SDK.
- Verificação de assinatura GPG (além do SHA-256 atual) para os binários do Node.js baixados.
- Imagem Docker de referência com Node.js pré-instalado, para ambientes que preferem não usar o
  download automático.
- Starter para Spring Boot (auto-configuração de `WhatsAppClient` a partir de `application.yml`).
- Ampliar a verificação manual do fluxo de código de pareamento com mais números/operadoras reais.

## Licença

[Apache License 2.0](LICENSE).
