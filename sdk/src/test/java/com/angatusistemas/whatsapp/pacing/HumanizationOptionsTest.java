package com.angatusistemas.whatsapp.pacing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HumanizationOptionsTest {

    /** Jitter previsivel: sempre o maximo possivel, para o teste ter borda exata. */
    private static final RandomGenerator JITTER_MAXIMO = new RandomGenerator() {
        @Override
        public long nextLong() {
            return 0;
        }

        @Override
        public long nextLong(long bound) {
            return bound - 1;
        }

        @Override
        public long nextLong(long origin, long bound) {
            return bound - 1;
        }
    };

    /** Sem jitter: isola a parte deterministica do calculo. */
    private static final RandomGenerator SEM_JITTER = new RandomGenerator() {
        @Override
        public long nextLong() {
            return 0;
        }

        @Override
        public long nextLong(long bound) {
            return 0;
        }

        @Override
        public long nextLong(long origin, long bound) {
            return origin;
        }
    };

    @Test
    @DisplayName("duracao acompanha o tamanho do texto, entre o piso e o teto")
    void duracaoAcompanhaOTexto() {
        HumanizationOptions o = HumanizationOptions.builder()
                .typingMsPerChar(25)
                .typingMin(Duration.ofSeconds(3))
                .typingMax(Duration.ofSeconds(9))
                .build();

        // 200 caracteres * 25ms = 5s, dentro da faixa: vale o calculado.
        assertEquals(5_000, o.duracaoDigitacao(200, SEM_JITTER).toMillis());
        // 10 caracteres = 250ms, abaixo do piso: sobe para 3s.
        assertEquals(3_000, o.duracaoDigitacao(10, SEM_JITTER).toMillis());
        // 10.000 caracteres = 250s, acima do teto: desce para 9s.
        assertEquals(9_000, o.duracaoDigitacao(10_000, SEM_JITTER).toMillis());
    }

    @Test
    @DisplayName("jitter soma no maximo 25% - ninguem digita duas mensagens no mesmo tempo exato")
    void jitterLimitadoAUmQuarto() {
        HumanizationOptions o = HumanizationOptions.builder()
                .typingMsPerChar(25)
                .typingMin(Duration.ofSeconds(3))
                .typingMax(Duration.ofSeconds(9))
                .build();

        long comJitter = o.duracaoDigitacao(200, JITTER_MAXIMO).toMillis();
        assertTrue(comJitter > 5_000, "o jitter tem de somar algo");
        assertTrue(comJitter < 5_000 + 5_000 / 4 + 1, "e nao pode passar de 25% (foi " + comJitter + "ms)");
    }

    @Test
    @DisplayName("digitacao desligada nao segura nada")
    void digitacaoDesligada() {
        HumanizationOptions desligada = HumanizationOptions.builder().typing(false).build();
        assertEquals(Duration.ZERO, desligada.duracaoDigitacao(500, JITTER_MAXIMO));

        HumanizationOptions semMsPorChar = HumanizationOptions.builder().typingMsPerChar(0).build();
        assertEquals(Duration.ZERO, semMsPorChar.duracaoDigitacao(500, JITTER_MAXIMO));
    }

    @Test
    @DisplayName("faixa invertida e corrigida em vez de derrubar a aplicacao")
    void faixaInvertidaECorrigida() {
        HumanizationOptions o = HumanizationOptions.builder()
                .typingMin(Duration.ofSeconds(9))
                .typingMax(Duration.ofSeconds(3))
                .build();
        assertEquals(o.typingMin(), o.typingMax(), "teto abaixo do piso vira espera fixa, nao excecao");

        HumanizationOptions negativa = HumanizationOptions.builder()
                .typingMin(Duration.ofSeconds(-5))
                .build();
        assertFalse(negativa.typingMin().isNegative());
    }

    @Test
    @DisplayName("padroes: digitando e gravando ligados, leitura MANUAL, presenca automatica desligada")
    void padroes() {
        HumanizationOptions o = HumanizationOptions.padrao();
        assertTrue(o.typing());
        assertTrue(o.recording());
        assertTrue(o.presenceAroundSend());
        // MANUAL e o unico padrao que nao muda o comportamento de quem ja usava
        // a SDK antes desta versao.
        assertEquals(ReadReceiptMode.MANUAL, o.readReceipts());
        // Assinar presenca custa uma consulta por conversa - e leitura em volume
        // e o que faz uma conta ser suspensa por coleta.
        assertFalse(o.autoSubscribePresence());
    }

    @Test
    @DisplayName("desligado() apaga todos os sinais")
    void desligadoApagaTudo() {
        HumanizationOptions o = HumanizationOptions.desligado();
        assertFalse(o.typing());
        assertFalse(o.recording());
        assertFalse(o.presenceAroundSend());
        assertFalse(o.autoSubscribePresence());
        assertEquals(ReadReceiptMode.OFF, o.readReceipts());
    }

    @Test
    @DisplayName("atraso de leitura cai dentro da faixa configurada")
    void atrasoDeLeituraNaFaixa() {
        HumanizationOptions o = HumanizationOptions.builder()
                .readDelay(Duration.ofSeconds(1), Duration.ofSeconds(4))
                .build();
        for (int i = 0; i < 200; i++) {
            long ms = o.atrasoLeitura(java.util.concurrent.ThreadLocalRandom.current()).toMillis();
            assertTrue(ms >= 1_000 && ms <= 4_000, "atraso fora da faixa: " + ms + "ms");
        }
    }
}
