package com.angatusistemas.whatsapp.pacing;

import java.time.Duration;

/**
 * Ritmo com que as mensagens saem de um numero: o intervalo entre uma e a
 * proxima, e o teto por minuto.
 *
 * <p><b>Por que isto e da biblioteca e nao da aplicacao.</b> Enviar em paralelo
 * ou em rajada e o comportamento que faz o WhatsApp classificar um numero como
 * automacao — e ate esta versao a SDK nao oferecia nada nesse sentido, entao
 * cada projeto que a usava precisava construir a propria fila (com o risco
 * obvio: o projeto que esquecesse ficava desprotegido sem perceber). Os padroes
 * daqui vieram de uma operacao real de prospeccao que rodou meses:
 * <b>4 a 12 segundos entre mensagens e no maximo 10 por minuto</b>.
 *
 * <p>A fila em si nao e opcional: mesmo com {@link #desligado()}, os envios de
 * uma instancia continuam saindo <b>um de cada vez</b>. Duas mensagens saindo no
 * mesmo segundo pelo mesmo numero e o padrao que denuncia robo, e nao ha caso de
 * uso que justifique. O que {@code desligado()} remove e a <b>espera</b>.
 *
 * <p>Imutavel. Para trocar o ritmo em tempo de execucao use
 * {@code WhatsAppClient.setPacing(...)}.
 */
public final class PacingOptions {

    private static final Duration PADRAO_MIN = Duration.ofSeconds(4);
    private static final Duration PADRAO_MAX = Duration.ofSeconds(12);
    private static final int PADRAO_POR_MINUTO = 10;

    private final Duration minDelay;
    private final Duration maxDelay;
    private final int maxPerMinute;

    private PacingOptions(Duration minDelay, Duration maxDelay, int maxPerMinute) {
        this.minDelay = minDelay;
        this.maxDelay = maxDelay;
        this.maxPerMinute = maxPerMinute;
    }

    /** Ritmo recomendado: 4 a 12 segundos entre envios, teto de 10 por minuto. */
    public static PacingOptions padrao() {
        return new PacingOptions(PADRAO_MIN, PADRAO_MAX, PADRAO_POR_MINUTO);
    }

    /**
     * Sem espera nenhuma: as mensagens saem assim que a fila as alcanca.
     *
     * <p>Para quem ja controla o ritmo por fora e nao quer as duas camadas se
     * somando. A serializacao continua valendo — ver o Javadoc da classe.
     */
    public static PacingOptions desligado() {
        return new PacingOptions(Duration.ZERO, Duration.ZERO, Integer.MAX_VALUE);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Espera minima entre dois envios que deram certo. */
    public Duration minDelay() {
        return minDelay;
    }

    /** Espera maxima entre dois envios que deram certo. */
    public Duration maxDelay() {
        return maxDelay;
    }

    /** Teto de envios por minuto, em janela deslizante de 60s. */
    public int maxPerMinute() {
        return maxPerMinute;
    }

    /** {@code true} quando nao ha espera alguma a aplicar. */
    public boolean semEspera() {
        return minDelay.isZero() && maxDelay.isZero();
    }

    @Override
    public String toString() {
        return "PacingOptions[" + minDelay.toMillis() + "-" + maxDelay.toMillis() + "ms, max "
                + (maxPerMinute == Integer.MAX_VALUE ? "ilimitado" : maxPerMinute) + "/min]";
    }

    public static final class Builder {

        private Duration minDelay = PADRAO_MIN;
        private Duration maxDelay = PADRAO_MAX;
        private int maxPerMinute = PADRAO_POR_MINUTO;

        private Builder() {
        }

        /** Espera minima entre envios. Padrao 4s. */
        public Builder minDelay(Duration minDelay) {
            this.minDelay = minDelay;
            return this;
        }

        /** Espera maxima entre envios. Padrao 12s. */
        public Builder maxDelay(Duration maxDelay) {
            this.maxDelay = maxDelay;
            return this;
        }

        /** Teto de envios por minuto. Padrao 10. */
        public Builder maxPerMinute(int maxPerMinute) {
            this.maxPerMinute = maxPerMinute;
            return this;
        }

        public PacingOptions build() {
            Duration min = normalizar(minDelay);
            Duration max = normalizar(maxDelay);
            // Intervalo invertido e erro de configuracao, mas derrubar a
            // aplicacao por causa dele seria pior que corrigi-lo: o pior
            // resultado de corrigir e uma espera fixa.
            if (max.compareTo(min) < 0) {
                max = min;
            }
            return new PacingOptions(min, max, maxPerMinute < 1 ? 1 : maxPerMinute);
        }

        private static Duration normalizar(Duration d) {
            return (d == null || d.isNegative()) ? Duration.ZERO : d;
        }
    }
}
