package com.angatusistemas.whatsapp;

import com.angatusistemas.whatsapp.exception.InstanceException;
import com.angatusistemas.whatsapp.model.Message;
import com.angatusistemas.whatsapp.model.MessageType;
import com.angatusistemas.whatsapp.pacing.HumanizationOptions;
import com.angatusistemas.whatsapp.pacing.ReadReceiptMode;
import com.angatusistemas.whatsapp.protocol.ProtocolMessage;
import com.angatusistemas.whatsapp.protocol.ProtocolVersion;
import com.angatusistemas.whatsapp.transport.BridgeConnection;
import com.angatusistemas.whatsapp.transport.MockBridgeServer;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cobre o ciclo de vida de recursos de {@link WhatsAppClient}: o que
 * {@link WhatsAppClient#connect()} reaproveita em vez de recriar, e o que
 * nunca pode ficar sem dono.
 *
 * <p><b>Por que ha reflexao aqui.</b> As decisoes que estes testes verificam
 * (subir ou nao um processo do bridge, trocar ou nao o WebSocket) acontecem
 * entre campos privados e um processo do sistema operacional. Um processo
 * real do bridge deixaria a suite lenta e dependente de ter Node instalado -
 * exatamente o que {@code MockBridgeServer} existe para evitar. A alternativa
 * seria abrir um ponto de injecao em {@link WhatsAppClient} que so os testes
 * usariam; entre as duas, a reflexao confinada ao teste incomoda menos do que
 * uma costura artificial no codigo de producao. O que sobra sem cobertura
 * automatizada esta anotado em cada teste.
 */
class WhatsAppClientLifecycleTest {

    private static final String TOKEN = "test-token-1234567890";

    // ------------------------------------------------------------- connect()

    /**
     * O caso deterministico que motivou esta correcao.
     *
     * <p>Antes, {@code connect()} caia incondicionalmente em
     * {@code startBridgeAndConnectInstance()}: todo ciclo de pausar e retomar
     * (o {@code disconnect()} -> {@code connect()} documentado em EXAMPLES.md)
     * subia um SEGUNDO processo Node por cima do primeiro, que continuava vivo
     * e sem nenhuma referencia. Nao dependia de corrida nenhuma - acontecia
     * toda vez.
     *
     * <p>Aqui a conexao com o bridge e injetada ja aberta, que e o estado em
     * que {@code disconnect()} deixa o cliente: ele so manda a acao pelo
     * WebSocket e nao toca no processo nem na conexao. O teste confirma que as
     * duas acoes chegam ao bridge pela MESMA conexao e que nenhum processo foi
     * criado no caminho.
     */
    @Test
    @Timeout(30)
    void connectDepoisDeDisconnectReaproveitaAConexaoQueJaEstaAberta(@TempDir Path sessionDir) throws Exception {
        try (MockBridgeServer server = MockBridgeServer.start()) {
            WhatsAppClient client = novoCliente("reuso-apos-disconnect", sessionDir, HumanizationOptions.padrao());
            BridgeConnection conexao = conectarAoMock(server);
            try {
                campo(client, "connection").set(client, conexao);

                client.disconnect();
                client.connect();

                assertEquals(List.of("disconnect", "connect"), acoesRecebidas(server, 2));
                assertNull(campo(client, "processManager").get(client),
                        "connect() nao pode subir um processo do bridge quando ja ha um WebSocket aberto");
            } finally {
                // Antes do close(): com a conexao aberta e um mock que nao
                // responde, close() esperaria os 15s dos dois timeouts de
                // encerramento educado.
                conexao.close();
                client.close();
            }
        }
    }

    /**
     * Duas chamadas simultaneas nao podem criar dois processos.
     *
     * <p><b>Alcance deste teste.</b> Ele verifica a trava, nao a corrida: a
     * bandeira de orquestracao e tomada de fora, simulando uma tentativa ja em
     * curso, e as duas chamadas concorrentes tem de ser recusadas sem criar
     * nada. Exercitar a corrida de verdade exigiria deixar uma das duas chegar
     * a {@code BridgeProcessManager.start()}, ou seja, subir Node - fora do
     * alcance desta suite. Essa metade esta coberta pela verificacao manual
     * descrita no README (rajada de {@code connect()} com {@code ps aux | grep
     * node} contando os processos).
     */
    @Test
    @Timeout(30)
    void connectConcorrenteNaoCriaProcessoComOrquestracaoEmAndamento(@TempDir Path sessionDir) throws Exception {
        WhatsAppClient client = novoCliente("connect-concorrente", sessionDir, HumanizationOptions.padrao());
        try {
            AtomicBoolean orquestrando = (AtomicBoolean) campo(client, "orchestratingConnection").get(client);
            assertTrue(orquestrando.compareAndSet(false, true), "a bandeira devia comecar livre");

            CountDownLatch largada = new CountDownLatch(1);
            CountDownLatch chegada = new CountDownLatch(2);
            AtomicInteger recusas = new AtomicInteger();
            for (int i = 0; i < 2; i++) {
                Thread t = new Thread(() -> {
                    try {
                        largada.await();
                        ExecutionException ex = assertThrows(ExecutionException.class,
                                () -> client.connect().get(10, TimeUnit.SECONDS));
                        assertInstanceOf(IllegalStateException.class, ex.getCause());
                        recusas.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        chegada.countDown();
                    }
                }, "connect-concorrente-" + i);
                t.setDaemon(true);
                t.start();
            }
            largada.countDown();
            assertTrue(chegada.await(20, TimeUnit.SECONDS), "as duas chamadas deviam ter retornado");

            assertEquals(2, recusas.get(), "as duas chamadas deviam ter sido recusadas de imediato");
            assertNull(campo(client, "processManager").get(client),
                    "nenhum processo do bridge pode ter sido criado");
        } finally {
            client.close();
        }
    }

    // ------------------------------------------------------- presencaAssinada

    /**
     * O conjunto de presencas assinadas tem teto.
     *
     * <p>Sem ele, um processo de vida longa com {@code autoSubscribePresence}
     * ligado acumulava um {@code String} por conversa distinta que ja tivesse
     * escrito, sem nunca soltar nenhum.
     *
     * <p>O cliente esta desconectado de proposito: assim
     * {@code subscribePresence()} falha de imediato e o teste nao depende de
     * bridge nenhum. A falha e sincrona (vem de {@code connection()}), entao
     * ela sobe pela chamada em vez de passar pelo {@code exceptionally} que
     * solta a marca - que e justamente o caminho em que o conjunto cresce.
     */
    @Test
    @Timeout(120)
    void presencaAssinadaNaoPassaDoTeto(@TempDir Path sessionDir) throws Exception {
        HumanizationOptions sinais = HumanizationOptions.builder()
                .autoSubscribePresence(true)
                .readReceipts(ReadReceiptMode.OFF)
                .build();
        WhatsAppClient client = novoCliente("teto-de-presencas", sessionDir, sinais);
        try {
            int teto = (int) campoEstatico("MAX_PRESENCAS_ASSINADAS").get(null);
            @SuppressWarnings("unchecked")
            Set<String> assinadas = (Set<String>) campo(client, "presencaAssinada").get(client);

            Method reagir = WhatsAppClient.class.getDeclaredMethod("reagirAMensagemRecebida", Message.class);
            reagir.setAccessible(true);

            int excedente = 10;
            for (int i = 0; i < teto + excedente; i++) {
                try {
                    reagir.invoke(client, mensagemDe("conversa-" + i + "@s.whatsapp.net"));
                } catch (InvocationTargetException esperado) {
                    // Desconectado: subscribePresence() lanca. E o que mantem o
                    // chatId no conjunto, que e o ponto do teste.
                }
                assertTrue(assinadas.size() <= teto,
                        "o conjunto passou do teto de " + teto + " na conversa " + i);
            }

            assertEquals(excedente, assinadas.size(),
                    "ao encher, o conjunto e esvaziado por inteiro e recomeca");
        } finally {
            client.close();
        }
    }

    // ------------------------------------------------------- thread da fila

    /**
     * Um {@code instanceId} repetido nao pode deixar a thread da fila orfa.
     *
     * <p>{@code SendPacer} sobe a thread no proprio construtor. Enquanto isso
     * vinha antes de {@code InstanceRegistry.register(...)}, um registro
     * recusado fazia o construtor do {@code WhatsAppClient} propagar a excecao
     * sem devolver objeto nenhum - e ninguem tinha como chamar
     * {@code pacer.close()} na thread que acabara de nascer.
     */
    @Test
    @Timeout(30)
    void registroRecusadoNaoDeixaAThreadDaFilaOrfa(@TempDir Path sessionDir) {
        String instanceId = "registro-duplicado";
        WhatsAppClient primeiro = novoCliente(instanceId, sessionDir, HumanizationOptions.padrao());
        try {
            long antes = threadsDaFila(instanceId);

            assertThrows(InstanceException.class,
                    () -> novoCliente(instanceId, sessionDir, HumanizationOptions.padrao()));

            assertEquals(antes, threadsDaFila(instanceId),
                    "a construcao recusada nao pode ter deixado uma thread de fila para tras");
        } finally {
            primeiro.close();
        }
    }

    // ---------------------------------------------------------------- apoio

    private static WhatsAppClient novoCliente(String instanceId, Path sessionDir, HumanizationOptions sinais) {
        return WhatsAppClient.builder()
                .instanceId(instanceId)
                .sessionDirectory(sessionDir)
                .humanization(sinais)
                .build();
    }

    private static long threadsDaFila(String instanceId) {
        String prefixo = "angatu-whatsapp-fila-" + instanceId;
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith(prefixo))
                .count();
    }

    private static Message mensagemDe(String chatId) {
        return new Message("msg-" + chatId, "qualquer", chatId, chatId, 0L, "oi",
                MessageType.TEXT, false, false, null, null, null, null);
    }

    /** Abre uma {@link BridgeConnection} de verdade contra o mock e completa o handshake. */
    private static BridgeConnection conectarAoMock(MockBridgeServer server) throws Exception {
        BridgeConnection conexao = new BridgeConnection(
                URI.create("ws://127.0.0.1:" + server.port()), TOKEN);
        var handshakeFuture = conexao.connect(Duration.ofSeconds(5));

        JsonNode handshake = server.nextMessage(Duration.ofSeconds(5));
        assertNotNull(handshake, "esperava receber a mensagem de handshake");
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("type", "handshake_ack");
        ack.put("id", handshake.path("id").asText());
        ack.put("protocolVersion", ProtocolVersion.CURRENT);
        ack.put("bridgeVersion", "test");
        ack.put("baileysVersion", "test");
        ack.put("pid", 1234);
        server.sendJson(ack);

        ProtocolMessage.HandshakeAck confirmado = handshakeFuture.get(5, TimeUnit.SECONDS);
        assertEquals("test", confirmado.bridgeVersion());
        assertTrue(conexao.isOpen());
        return conexao;
    }

    /** As proximas {@code quantidade} acoes que chegaram ao bridge, na ordem. */
    private static List<String> acoesRecebidas(MockBridgeServer server, int quantidade) throws InterruptedException {
        List<String> acoes = new ArrayList<>();
        while (acoes.size() < quantidade) {
            JsonNode mensagem = server.nextMessage(Duration.ofSeconds(5));
            assertNotNull(mensagem, "esperava " + quantidade + " acoes, chegaram " + acoes);
            if ("request".equals(mensagem.path("type").asText())) {
                acoes.add(mensagem.path("action").asText());
            }
        }
        return acoes;
    }

    private static Field campo(WhatsAppClient client, String nome) throws Exception {
        Field field = client.getClass().getDeclaredField(nome);
        field.setAccessible(true);
        return field;
    }

    private static Field campoEstatico(String nome) throws Exception {
        Field field = WhatsAppClient.class.getDeclaredField(nome);
        field.setAccessible(true);
        return field;
    }
}
