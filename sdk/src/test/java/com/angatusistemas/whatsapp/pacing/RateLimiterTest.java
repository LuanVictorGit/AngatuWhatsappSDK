package com.angatusistemas.whatsapp.pacing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    @Test
    @DisplayName("libera ate o teto e recusa a proxima")
    void liberaAteOTeto() {
        RateLimiter limite = new RateLimiter(3);

        assertTrue(limite.tentarVaga());
        assertTrue(limite.tentarVaga());
        assertTrue(limite.tentarVaga());
        assertFalse(limite.tentarVaga(), "a quarta nao cabe numa janela de 3");
    }

    @Test
    @DisplayName("vagasDisponiveis acompanha o consumo")
    void vagasAcompanhamOConsumo() {
        RateLimiter limite = new RateLimiter(2);

        assertEquals(2, limite.vagasDisponiveis());
        limite.tentarVaga();
        assertEquals(1, limite.vagasDisponiveis());
        limite.tentarVaga();
        assertEquals(0, limite.vagasDisponiveis());
    }

    @Test
    @DisplayName("teto zero ou negativo vira 1, nunca uma fila travada para sempre")
    void tetoInvalidoViraUm() {
        assertEquals(1, new RateLimiter(0).maxPorMinuto());
        assertEquals(1, new RateLimiter(-5).maxPorMinuto());
        // E o limite de 1 tem de funcionar de verdade, nao so no getter.
        RateLimiter limite = new RateLimiter(0);
        assertTrue(limite.tentarVaga());
        assertFalse(limite.tentarVaga());
    }

    @Test
    @DisplayName("aguardarVaga passa direto enquanto ha folga")
    void aguardarVagaNaoSeguraQuandoHaFolga() throws Exception {
        RateLimiter limite = new RateLimiter(5);
        long inicio = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            limite.aguardarVaga();
        }
        long decorridoMs = (System.nanoTime() - inicio) / 1_000_000;
        assertTrue(decorridoMs < 500, "cinco vagas livres nao deviam custar espera (levou " + decorridoMs + "ms)");
    }

    @Test
    @DisplayName("nenhuma vaga e concedida duas vezes sob concorrencia")
    void naoConcedeAMesmaVagaDuasVezes() throws Exception {
        // A marca e registrada DENTRO do synchronized, junto da conferencia -
        // se fosse registrada depois, duas threads passariam pela ultima vaga.
        int teto = 10;
        int threads = 40;
        RateLimiter limite = new RateLimiter(teto);
        AtomicInteger concedidas = new AtomicInteger();
        CountDownLatch largada = new CountDownLatch(1);
        CountDownLatch fim = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    largada.await();
                    if (limite.tentarVaga()) {
                        concedidas.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    fim.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }

        largada.countDown();
        assertTrue(fim.await(10, TimeUnit.SECONDS), "as threads deviam terminar");
        assertEquals(teto, concedidas.get(), "exatamente o teto de vagas, nem uma a mais");
    }
}
