package com.angatusistemas.whatsapp.pacing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SendPacerTest {

    /** Backend de mentira: registra o que a fila pediu, sem falar com bridge nenhum. */
    private static final class BackendFalso implements SendPacer.PacerBackend {
        final List<String> presencas = new CopyOnWriteArrayList<>();
        final List<String> lidas = new CopyOnWriteArrayList<>();
        volatile boolean falharPresenca;

        @Override
        public CompletableFuture<Void> sendPresence(String chatId, String presence) {
            if (falharPresenca) {
                return CompletableFuture.failedFuture(new IllegalStateException("sem conexao"));
            }
            presencas.add(presence + "@" + chatId);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> markChatRead(String chatId) {
            lidas.add(chatId);
            return CompletableFuture.completedFuture(null);
        }
    }

    /** Ritmo sem espera e sem simulacao, para testar so a serializacao. */
    private static HumanizationOptions semSinais() {
        return HumanizationOptions.desligado();
    }

    @Test
    @DisplayName("nada sai em paralelo, mesmo com varias threads enfileirando")
    void nadaSaiEmParalelo() throws Exception {
        BackendFalso backend = new BackendFalso();
        AtomicInteger emVoo = new AtomicInteger();
        AtomicInteger picoSimultaneo = new AtomicInteger();

        try (SendPacer pacer = new SendPacer("t1", backend, PacingOptions.desligado(), semSinais())) {
            CountDownLatch fim = new CountDownLatch(20);
            for (int i = 0; i < 20; i++) {
                pacer.enqueue("5562999@s.whatsapp.net", QueuedSend.Kind.TEXT, 10, () -> {
                    int agora = emVoo.incrementAndGet();
                    picoSimultaneo.accumulateAndGet(agora, Math::max);
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    emVoo.decrementAndGet();
                    return CompletableFuture.completedFuture("id");
                }).result().whenComplete((r, e) -> fim.countDown());
            }
            assertTrue(fim.await(30, TimeUnit.SECONDS), "os 20 envios deviam terminar");
        }

        // Duas mensagens no mesmo segundo pelo mesmo numero e o padrao que
        // denuncia robo: a fila existe exatamente para que isto seja sempre 1.
        assertEquals(1, picoSimultaneo.get(), "nunca pode haver dois envios simultaneos");
    }

    @Test
    @DisplayName("respeita o intervalo minimo entre envios que deram certo")
    void respeitaOIntervalo() throws Exception {
        BackendFalso backend = new BackendFalso();
        PacingOptions ritmo = PacingOptions.builder()
                .minDelay(Duration.ofMillis(300))
                .maxDelay(Duration.ofMillis(300))
                .maxPerMinute(1000)
                .build();

        List<Long> instantes = new CopyOnWriteArrayList<>();
        try (SendPacer pacer = new SendPacer("t2", backend, ritmo, semSinais())) {
            CountDownLatch fim = new CountDownLatch(3);
            for (int i = 0; i < 3; i++) {
                pacer.enqueue("chat", QueuedSend.Kind.TEXT, 5, () -> {
                    instantes.add(System.currentTimeMillis());
                    return CompletableFuture.completedFuture("id");
                }).result().whenComplete((r, e) -> fim.countDown());
            }
            assertTrue(fim.await(30, TimeUnit.SECONDS));
        }

        assertEquals(3, instantes.size());
        // O primeiro sai na hora; do segundo em diante, o espacamento vale.
        long gap1 = instantes.get(1) - instantes.get(0);
        long gap2 = instantes.get(2) - instantes.get(1);
        assertTrue(gap1 >= 250, "segundo envio saiu cedo demais: " + gap1 + "ms");
        assertTrue(gap2 >= 250, "terceiro envio saiu cedo demais: " + gap2 + "ms");
    }

    @Test
    @DisplayName("falha de envio NAO gasta o intervalo humano")
    void falhaNaoGastaOIntervalo() throws Exception {
        // O espacamento existe para quem recebeu. Se nada chegou a ninguem, nao
        // ha por que a proxima tentativa esperar - so gastaria a janela do dia.
        BackendFalso backend = new BackendFalso();
        PacingOptions ritmo = PacingOptions.builder()
                .minDelay(Duration.ofSeconds(5))
                .maxDelay(Duration.ofSeconds(5))
                .maxPerMinute(1000)
                .build();

        try (SendPacer pacer = new SendPacer("t3", backend, ritmo, semSinais())) {
            long inicio = System.currentTimeMillis();
            CountDownLatch fim = new CountDownLatch(2);

            pacer.enqueue("chat", QueuedSend.Kind.TEXT, 5,
                    () -> CompletableFuture.failedFuture(new IllegalStateException("recusado")))
                    .result().whenComplete((r, e) -> fim.countDown());
            pacer.enqueue("chat", QueuedSend.Kind.TEXT, 5,
                    () -> CompletableFuture.completedFuture("id"))
                    .result().whenComplete((r, e) -> fim.countDown());

            assertTrue(fim.await(30, TimeUnit.SECONDS));
            long decorrido = System.currentTimeMillis() - inicio;
            assertTrue(decorrido < 4_000,
                    "o envio apos uma falha nao devia esperar os 5s (levou " + decorrido + "ms)");
        }
    }

    @Test
    @DisplayName("publica composing antes e paused/unavailable depois")
    void publicaPresencaEmVoltaDoEnvio() throws Exception {
        BackendFalso backend = new BackendFalso();
        HumanizationOptions sinais = HumanizationOptions.builder()
                .typingMsPerChar(1)
                .typingMin(Duration.ofMillis(60))
                .typingMax(Duration.ofMillis(60))
                .build();

        try (SendPacer pacer = new SendPacer("t4", backend, PacingOptions.desligado(), sinais)) {
            pacer.enqueue("chat", QueuedSend.Kind.TEXT, 10,
                    () -> CompletableFuture.completedFuture("id"))
                    .result().get(30, TimeUnit.SECONDS);
        }

        assertTrue(backend.presencas.contains("composing@chat"), "devia ter publicado composing");
        assertTrue(backend.presencas.contains("paused@chat"), "devia ter encerrado com paused");
        // O unavailable e o que impede o numero de ficar online 24h por dia -
        // que e, por si so, assinatura de automacao.
        assertTrue(backend.presencas.contains("unavailable@chat"), "devia voltar a offline apos o envio");
    }

    @Test
    @DisplayName("audio publica recording, nao composing")
    void audioPublicaRecording() throws Exception {
        BackendFalso backend = new BackendFalso();
        HumanizationOptions sinais = HumanizationOptions.builder()
                .typingMin(Duration.ofMillis(50))
                .typingMax(Duration.ofMillis(50))
                .build();

        try (SendPacer pacer = new SendPacer("t5", backend, PacingOptions.desligado(), sinais)) {
            pacer.enqueue("chat", QueuedSend.Kind.AUDIO, 0,
                    () -> CompletableFuture.completedFuture("id"))
                    .result().get(30, TimeUnit.SECONDS);
        }

        assertTrue(backend.presencas.stream().anyMatch(p -> p.startsWith("recording@")),
                "audio devia mostrar 'gravando audio...'");
        assertFalse(backend.presencas.stream().anyMatch(p -> p.startsWith("composing@")),
                "audio nao devia mostrar 'digitando...'");
    }

    @Test
    @DisplayName("a espera acontece mesmo quando a presenca falha")
    void esperaNaoDependeDaPresenca() throws Exception {
        // Sem isto, uma falha ao publicar a presenca fazia a mensagem sair
        // instantaneamente: o pior dos dois mundos, sem indicador E sem pausa.
        BackendFalso backend = new BackendFalso();
        backend.falharPresenca = true;
        HumanizationOptions sinais = HumanizationOptions.builder()
                .typingMsPerChar(1)
                .typingMin(Duration.ofMillis(400))
                .typingMax(Duration.ofMillis(400))
                .build();

        try (SendPacer pacer = new SendPacer("t6", backend, PacingOptions.desligado(), sinais)) {
            long inicio = System.currentTimeMillis();
            pacer.enqueue("chat", QueuedSend.Kind.TEXT, 10,
                    () -> CompletableFuture.completedFuture("id"))
                    .result().get(30, TimeUnit.SECONDS);
            long decorrido = System.currentTimeMillis() - inicio;
            assertTrue(decorrido >= 350, "a pausa devia acontecer assim mesmo (levou " + decorrido + "ms)");
        }
    }

    @Test
    @DisplayName("ON_REPLY marca a conversa como lida ANTES de enviar")
    void onReplyMarcaLidaAntes() throws Exception {
        BackendFalso backend = new BackendFalso();
        List<String> ordem = new CopyOnWriteArrayList<>();
        HumanizationOptions sinais = HumanizationOptions.builder()
                .typing(false)
                .recording(false)
                .presenceAroundSend(false)
                .readReceipts(ReadReceiptMode.ON_REPLY)
                .readDelay(Duration.ZERO, Duration.ZERO)
                .build();

        SendPacer.PacerBackend espiao = new SendPacer.PacerBackend() {
            @Override
            public CompletableFuture<Void> sendPresence(String chatId, String presence) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> markChatRead(String chatId) {
                ordem.add("lida");
                return backend.markChatRead(chatId);
            }
        };

        try (SendPacer pacer = new SendPacer("t7", espiao, PacingOptions.desligado(), sinais)) {
            pacer.enqueue("chat", QueuedSend.Kind.TEXT, 5, () -> {
                ordem.add("enviada");
                return CompletableFuture.completedFuture("id");
            }).result().get(30, TimeUnit.SECONDS);
        }

        // Leu, depois respondeu: e a sequencia humana.
        assertEquals(List.of("lida", "enviada"), ordem);
    }

    @Test
    @DisplayName("MANUAL nao marca nada sozinho")
    void manualNaoMarcaNada() throws Exception {
        BackendFalso backend = new BackendFalso();
        try (SendPacer pacer = new SendPacer("t8", backend, PacingOptions.desligado(), semSinais())) {
            pacer.enqueue("chat", QueuedSend.Kind.TEXT, 5,
                    () -> CompletableFuture.completedFuture("id"))
                    .result().get(30, TimeUnit.SECONDS);
        }
        assertTrue(backend.lidas.isEmpty(), "no modo manual a SDK nao marca leitura sozinha");
    }

    @Test
    @DisplayName("item na fila pode ser cancelado; o future falha")
    void cancelamento() throws Exception {
        BackendFalso backend = new BackendFalso();
        PacingOptions lento = PacingOptions.builder()
                .minDelay(Duration.ofSeconds(3))
                .maxDelay(Duration.ofSeconds(3))
                .maxPerMinute(1000)
                .build();

        try (SendPacer pacer = new SendPacer("t9", backend, lento, semSinais())) {
            pacer.enqueue("chat", QueuedSend.Kind.TEXT, 5, () -> CompletableFuture.completedFuture("um"));
            QueuedSend segundo = pacer.enqueue("chat", QueuedSend.Kind.TEXT, 5,
                    () -> CompletableFuture.completedFuture("dois"));

            assertTrue(pacer.cancel(segundo.id()), "o segundo ainda nao tinha sido entregue ao WhatsApp");
            // O cancelamento e atendido pela thread da FILA, nao por quem
            // cancelou: e o que mantem a conclusao do future num lugar so, em
            // vez de duas threads disputando o mesmo item. Entao espera-se o
            // future em vez de conferir o status na hora.
            assertThrows(Exception.class, () -> segundo.result().get(5, TimeUnit.SECONDS));
            assertEquals(QueuedSend.Status.CANCELLED, segundo.status());
            assertFalse(pacer.cancel(segundo.id()), "item ja encerrado nao e cancelavel de novo");
        }
    }

    @Test
    @DisplayName("close() completa os futures pendentes em vez de deixa-los pendurados")
    void closeNaoDeixaFuturePendurado() throws Exception {
        BackendFalso backend = new BackendFalso();
        PacingOptions lento = PacingOptions.builder()
                .minDelay(Duration.ofSeconds(10))
                .maxDelay(Duration.ofSeconds(10))
                .maxPerMinute(1000)
                .build();

        SendPacer pacer = new SendPacer("t10", backend, lento, semSinais());
        pacer.enqueue("chat", QueuedSend.Kind.TEXT, 5, () -> CompletableFuture.completedFuture("um"));
        QueuedSend pendente = pacer.enqueue("chat", QueuedSend.Kind.TEXT, 5,
                () -> CompletableFuture.completedFuture("dois"));
        pacer.close();

        // Quem chamou send*() e esta bloqueado em .get() nao pode ficar assim
        // para sempre depois de um close().
        assertThrows(Exception.class, () -> pendente.result().get(5, TimeUnit.SECONDS));
        assertTrue(pendente.result().isDone());
    }

    @Test
    @DisplayName("enqueue apos close falha na hora, sem engolir a mensagem")
    void enqueueAposCloseFalha() {
        BackendFalso backend = new BackendFalso();
        SendPacer pacer = new SendPacer("t11", backend, PacingOptions.desligado(), semSinais());
        pacer.close();

        QueuedSend item = pacer.enqueue("chat", QueuedSend.Kind.TEXT, 5,
                () -> CompletableFuture.completedFuture("id"));
        assertEquals(QueuedSend.Status.CANCELLED, item.status());
        assertTrue(item.result().isCompletedExceptionally());
    }

    @Test
    @DisplayName("pending() mostra o que ainda nao saiu")
    void pendingMostraAFila() {
        BackendFalso backend = new BackendFalso();
        PacingOptions lento = PacingOptions.builder()
                .minDelay(Duration.ofSeconds(5))
                .maxDelay(Duration.ofSeconds(5))
                .maxPerMinute(1000)
                .build();

        try (SendPacer pacer = new SendPacer("t12", backend, lento, semSinais())) {
            pacer.enqueue("a", QueuedSend.Kind.TEXT, 5, () -> CompletableFuture.completedFuture("1"));
            pacer.enqueue("b", QueuedSend.Kind.TEXT, 5, () -> CompletableFuture.completedFuture("2"));
            pacer.enqueue("c", QueuedSend.Kind.TEXT, 5, () -> CompletableFuture.completedFuture("3"));

            assertTrue(pacer.pending().size() >= 2, "a fila devia mostrar os itens esperando");
            int descartados = pacer.clear();
            assertTrue(descartados >= 2, "clear() devia descartar os pendentes");
        }
    }
}
