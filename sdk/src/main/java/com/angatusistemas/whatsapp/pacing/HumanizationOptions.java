package com.angatusistemas.whatsapp.pacing;

import java.time.Duration;

/**
 * Os sinais que a conta emite em volta de um envio: "digitando...",
 * "gravando audio...", online/offline e o "azulzinho" de leitura.
 *
 * <p>Cada um liga e desliga sozinho. Todos vem <b>ligados</b> por padrao, exceto
 * {@link #readReceipts()} (que fica em {@link ReadReceiptMode#MANUAL}, para nao
 * mudar o comportamento de quem ja usava a SDK) e
 * {@link #autoSubscribePresence()} (que custa uma consulta por conversa).
 *
 * <p><b>Tres detalhes que custam caro e falham em silencio</b> — sem erro em
 * lugar nenhum, o indicador simplesmente nao aparece:
 *
 * <ol>
 *   <li>com {@code markOnlineOnConnect=false}, que e o padrao desta SDK para o
 *       numero nao ficar online 24 horas por dia, o WhatsApp <b>descarta</b> o
 *       {@code composing} de um socket offline. Por isso {@link #presenceAroundSend()}
 *       existe e vem ligado: e ele que anuncia {@code available} antes;</li>
 *   <li>o endereco do {@code chatstate} nao e resolvido pelo servidor — chega
 *       literal. Numa conversa ja migrada para LID, um {@code composing} enviado
 *       ao telefone nunca chega ao chat aberto. O bridge resolve isso sozinho;</li>
 *   <li>a conta precisa ter <b>nome de perfil</b>. O Baileys recusa
 *       {@code available}/{@code unavailable} quando {@code creds.me.name} esta
 *       vazio, e apenas registra um aviso.</li>
 * </ol>
 *
 * <p>Imutavel. Para trocar em tempo de execucao use
 * {@code WhatsAppClient.setHumanization(...)}.
 */
public final class HumanizationOptions {

    private static final long PADRAO_MS_POR_CARACTERE = 25;
    private static final Duration PADRAO_TYPING_MIN = Duration.ofSeconds(3);
    private static final Duration PADRAO_TYPING_MAX = Duration.ofSeconds(9);
    private static final Duration PADRAO_READ_DELAY_MIN = Duration.ofSeconds(1);
    private static final Duration PADRAO_READ_DELAY_MAX = Duration.ofSeconds(4);

    private final boolean typing;
    private final long typingMsPerChar;
    private final Duration typingMin;
    private final Duration typingMax;
    private final boolean recording;
    private final boolean presenceAroundSend;
    private final Duration onlineHoldAfterSend;
    private final ReadReceiptMode readReceipts;
    private final Duration readDelayMin;
    private final Duration readDelayMax;
    private final boolean autoSubscribePresence;

    private HumanizationOptions(Builder b) {
        this.typing = b.typing;
        this.typingMsPerChar = b.typingMsPerChar;
        this.typingMin = b.typingMin;
        this.typingMax = b.typingMax;
        this.recording = b.recording;
        this.presenceAroundSend = b.presenceAroundSend;
        this.onlineHoldAfterSend = b.onlineHoldAfterSend;
        this.readReceipts = b.readReceipts;
        this.readDelayMin = b.readDelayMin;
        this.readDelayMax = b.readDelayMax;
        this.autoSubscribePresence = b.autoSubscribePresence;
    }

    /** Configuracao recomendada: digitando e gravando ligados, leitura manual. */
    public static HumanizationOptions padrao() {
        return builder().build();
    }

    /**
     * Nenhum sinal: sem "digitando...", sem presenca, sem azulzinho.
     *
     * <p>Para quem ja publica esses sinais por fora e nao quer os dois fazendo a
     * mesma coisa. <b>Atencao:</b> isto desliga o indicador, nao a espera — o
     * ritmo continua sendo o do {@link PacingOptions}.
     */
    public static HumanizationOptions desligado() {
        return builder()
                .typing(false)
                .recording(false)
                .presenceAroundSend(false)
                .readReceipts(ReadReceiptMode.OFF)
                .autoSubscribePresence(false)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Se publica "digitando..." e segura a mensagem pelo tempo de digitacao. */
    public boolean typing() {
        return typing;
    }

    /** Milissegundos de "digitacao" por caractere do texto. Padrao 25. */
    public long typingMsPerChar() {
        return typingMsPerChar;
    }

    /** Piso da duracao do "digitando...". Padrao 3s. */
    public Duration typingMin() {
        return typingMin;
    }

    /** Teto da duracao do "digitando...". Padrao 9s. */
    public Duration typingMax() {
        return typingMax;
    }

    /** Se envio de audio publica "gravando audio..." no lugar de "digitando...". */
    public boolean recording() {
        return recording;
    }

    /** Se declara {@code available} antes e {@code unavailable} depois do envio. */
    public boolean presenceAroundSend() {
        return presenceAroundSend;
    }

    /** Quanto tempo segue online apos o envio antes de voltar a {@code unavailable}. */
    public Duration onlineHoldAfterSend() {
        return onlineHoldAfterSend;
    }

    /** Quando marcar mensagens recebidas como lidas. */
    public ReadReceiptMode readReceipts() {
        return readReceipts;
    }

    /** Piso do atraso antes de marcar como lida nos modos automaticos. */
    public Duration readDelayMin() {
        return readDelayMin;
    }

    /** Teto do atraso antes de marcar como lida nos modos automaticos. */
    public Duration readDelayMax() {
        return readDelayMax;
    }

    /** Se assina a presenca de uma conversa ao receber a primeira mensagem dela. */
    public boolean autoSubscribePresence() {
        return autoSubscribePresence;
    }

    /**
     * Duracao do indicador para um texto de {@code tamanho} caracteres, ja com o
     * jitter aplicado.
     *
     * <p>O jitter (ate +25%) existe porque ninguem digita duas mensagens no
     * mesmo tempo exato — uma duracao deterministica seria, ela propria, um
     * padrao reconhecivel.
     *
     * @param tamanho numero de caracteres do texto (0 para midia sem legenda)
     * @param aleatorio fonte de aleatoriedade
     * @return duracao a segurar, ou {@link Duration#ZERO} se a simulacao esta
     *         desligada
     */
    public Duration duracaoDigitacao(int tamanho, java.util.random.RandomGenerator aleatorio) {
        if (!typing || typingMsPerChar <= 0) {
            return Duration.ZERO;
        }
        long bruto = Math.max(0L, (long) tamanho * typingMsPerChar);
        long comLimites = Math.min(typingMax.toMillis(), Math.max(typingMin.toMillis(), bruto));
        if (comLimites <= 0) {
            return Duration.ZERO;
        }
        long jitter = aleatorio.nextLong(Math.max(1L, comLimites / 4));
        return Duration.ofMillis(comLimites + jitter);
    }

    /** Atraso sorteado antes de marcar como lida. */
    public Duration atrasoLeitura(java.util.random.RandomGenerator aleatorio) {
        long min = readDelayMin.toMillis();
        long max = readDelayMax.toMillis();
        if (max <= min) {
            return Duration.ofMillis(Math.max(0, min));
        }
        return Duration.ofMillis(aleatorio.nextLong(min, max + 1));
    }

    public static final class Builder {

        private boolean typing = true;
        private long typingMsPerChar = PADRAO_MS_POR_CARACTERE;
        private Duration typingMin = PADRAO_TYPING_MIN;
        private Duration typingMax = PADRAO_TYPING_MAX;
        private boolean recording = true;
        private boolean presenceAroundSend = true;
        private Duration onlineHoldAfterSend = Duration.ZERO;
        private ReadReceiptMode readReceipts = ReadReceiptMode.MANUAL;
        private Duration readDelayMin = PADRAO_READ_DELAY_MIN;
        private Duration readDelayMax = PADRAO_READ_DELAY_MAX;
        private boolean autoSubscribePresence = false;

        private Builder() {
        }

        /** Liga/desliga o "digitando...". Desligado, a espera do ritmo continua. */
        public Builder typing(boolean typing) {
            this.typing = typing;
            return this;
        }

        /**
         * Milissegundos por caractere. Padrao 25 — poe uma mensagem tipica de
         * ~200 caracteres em torno de 5 segundos, que acompanha o tamanho do
         * texto sem deixar o contato quinze segundos olhando "digitando...".
         * Zero desliga a simulacao.
         */
        public Builder typingMsPerChar(long typingMsPerChar) {
            this.typingMsPerChar = typingMsPerChar;
            return this;
        }

        /** Piso da duracao do indicador. Padrao 3s. */
        public Builder typingMin(Duration typingMin) {
            this.typingMin = typingMin;
            return this;
        }

        /** Teto da duracao do indicador. Padrao 9s. */
        public Builder typingMax(Duration typingMax) {
            this.typingMax = typingMax;
            return this;
        }

        /** Liga/desliga o "gravando audio..." nos envios de audio. */
        public Builder recording(boolean recording) {
            this.recording = recording;
            return this;
        }

        /**
         * Liga/desliga o {@code available}/{@code unavailable} em volta do envio.
         *
         * <p>Desligar tambem apaga o "digitando..." na pratica: o WhatsApp
         * descarta {@code composing} vindo de socket offline.
         */
        public Builder presenceAroundSend(boolean presenceAroundSend) {
            this.presenceAroundSend = presenceAroundSend;
            return this;
        }

        /**
         * Quanto tempo o numero segue online depois do envio. Padrao zero (sai
         * de online na hora).
         *
         * <p>Alguns segundos parecem mais naturais numa conversa de ida e volta;
         * o que nao pode e ficar online o tempo todo, que e o padrao de robo.
         */
        public Builder onlineHoldAfterSend(Duration onlineHoldAfterSend) {
            this.onlineHoldAfterSend = onlineHoldAfterSend;
            return this;
        }

        /** Quando marcar como lida. Padrao {@link ReadReceiptMode#MANUAL}. */
        public Builder readReceipts(ReadReceiptMode readReceipts) {
            this.readReceipts = readReceipts;
            return this;
        }

        /** Faixa do atraso antes de marcar como lida. Padrao 1s a 4s. */
        public Builder readDelay(Duration min, Duration max) {
            this.readDelayMin = min;
            this.readDelayMax = max;
            return this;
        }

        /**
         * Assina a presenca de uma conversa ao receber a primeira mensagem dela,
         * para o {@code PresenceListener} passar a receber o "digitando..." do
         * contato. Padrao desligado.
         *
         * <p>Desligado por padrao porque custa uma consulta por conversa, e
         * leitura em volume e o que faz uma conta ser suspensa por coleta. Para
         * acompanhar apenas as conversas que importam, prefira chamar
         * {@code subscribePresence(chatId)} nelas.
         */
        public Builder autoSubscribePresence(boolean autoSubscribePresence) {
            this.autoSubscribePresence = autoSubscribePresence;
            return this;
        }

        public HumanizationOptions build() {
            this.typingMin = naoNegativo(typingMin, PADRAO_TYPING_MIN);
            this.typingMax = naoNegativo(typingMax, PADRAO_TYPING_MAX);
            if (typingMax.compareTo(typingMin) < 0) {
                this.typingMax = typingMin;
            }
            this.readDelayMin = naoNegativo(readDelayMin, PADRAO_READ_DELAY_MIN);
            this.readDelayMax = naoNegativo(readDelayMax, PADRAO_READ_DELAY_MAX);
            if (readDelayMax.compareTo(readDelayMin) < 0) {
                this.readDelayMax = readDelayMin;
            }
            this.onlineHoldAfterSend = naoNegativo(onlineHoldAfterSend, Duration.ZERO);
            if (readReceipts == null) {
                this.readReceipts = ReadReceiptMode.MANUAL;
            }
            if (typingMsPerChar < 0) {
                this.typingMsPerChar = 0;
            }
            return new HumanizationOptions(this);
        }

        private static Duration naoNegativo(Duration d, Duration padrao) {
            if (d == null) {
                return padrao;
            }
            return d.isNegative() ? Duration.ZERO : d;
        }
    }
}
