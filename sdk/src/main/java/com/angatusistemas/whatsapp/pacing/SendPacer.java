package com.angatusistemas.whatsapp.pacing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * A fila unica de envio de uma instancia: <b>uma thread, nada em paralelo</b>.
 *
 * <p>Existe por dois motivos que a SDK nao cobria:
 *
 * <ol>
 *   <li><b>Nada sai ao mesmo tempo.</b> Antes, N threads da aplicacao chamando
 *       {@code sendText} batiam todas no socket juntas — duas mensagens no mesmo
 *       segundo pelo mesmo numero e justamente o padrao que denuncia robo. Agora
 *       tudo passa por aqui: o segundo envio espera o primeiro terminar, mais o
 *       intervalo e o teto por minuto, venha de onde vier.</li>
 *   <li><b>"Digitando..." antes de cada mensagem.</b> A fila publica a presenca
 *       no destino, segura pelo tempo que uma pessoa levaria para digitar aquele
 *       texto e so entao envia, fechando com {@code paused} e
 *       {@code unavailable}.</li>
 * </ol>
 *
 * <p>A fila e <b>em memoria de proposito</b>. O que precisa sobreviver a um
 * restart e responsabilidade de quem usa a SDK (uma campanha tem o progresso
 * dela no proprio banco); guardar a fila em disco aqui daria a impressao de uma
 * garantia de entrega que esta camada nao tem como dar.
 */
public final class SendPacer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SendPacer.class);

    /**
     * De quanto em quanto tempo o "digitando..." e renovado.
     *
     * <p>O WhatsApp apaga o indicador sozinho em cerca de 10 segundos; renovar a
     * cada 8 o mantem aceso num texto longo sem virar uma enxurrada de stanzas.
     */
    private static final long RENOVAR_PRESENCA_MS = 8_000;

    private final String instanceId;
    private final PacerBackend backend;
    private final LinkedBlockingDeque<QueuedSend> fila = new LinkedBlockingDeque<>();
    private final AtomicLong sequencia = new AtomicLong();
    private final AtomicBoolean encerrado = new AtomicBoolean(false);
    private final Thread worker;

    private volatile PacingOptions pacing;
    private volatile HumanizationOptions humanization;
    private volatile RateLimiter limite;
    private volatile QueuedSend atual;
    /** Instante do ultimo envio que REALMENTE saiu. Ver {@link #esperarIntervalo()}. */
    private volatile long ultimoEnvioMs;

    /**
     * O que a fila precisa do cliente para publicar presenca e marcar leitura,
     * sem depender do {@code WhatsAppClient} (que depende dela).
     */
    public interface PacerBackend {
        /** Publica uma presenca. Falha aqui nunca pode abortar o envio. */
        CompletableFuture<Void> sendPresence(String chatId, String presence);

        /** Marca a conversa como lida (usado pelo modo {@code ON_REPLY}). */
        CompletableFuture<Void> markChatRead(String chatId);
    }

    public SendPacer(String instanceId, PacerBackend backend, PacingOptions pacing, HumanizationOptions humanization) {
        this.instanceId = instanceId;
        this.backend = backend;
        this.pacing = pacing != null ? pacing : PacingOptions.padrao();
        this.humanization = humanization != null ? humanization : HumanizationOptions.padrao();
        this.limite = new RateLimiter(this.pacing.maxPerMinute());
        this.worker = new Thread(this::rodar, "angatu-whatsapp-fila-" + instanceId);
        this.worker.setDaemon(true);
        this.worker.start();
    }

    // ---------------------------------------------------------------- entrada

    /**
     * Poe um envio na fila.
     *
     * @param chatId destino
     * @param kind que sinal de presenca publicar
     * @param textLength tamanho do texto/legenda, para a duracao da digitacao
     * @param envio a chamada real ao bridge, executada quando chegar a vez
     * @return o item enfileirado; {@link QueuedSend#result()} completa no fim
     */
    public QueuedSend enqueue(String chatId, QueuedSend.Kind kind, int textLength,
                              Supplier<CompletableFuture<String>> envio) {
        QueuedSend item = new QueuedSend("q" + sequencia.incrementAndGet(), chatId, kind, textLength, envio);
        if (encerrado.get()) {
            item.marcar(QueuedSend.Status.CANCELLED);
            item.resultado().completeExceptionally(
                    new CancellationException("cliente ja encerrado; envio para " + chatId + " descartado"));
            return item;
        }
        fila.addLast(item);
        return item;
    }

    // ---------------------------------------------------------------- operacao

    /** Itens que ainda esperam a vez, mais o que esta em andamento. */
    public List<QueuedSend> pending() {
        List<QueuedSend> saida = new ArrayList<>();
        QueuedSend emAndamento = atual;
        if (emAndamento != null && emAndamento.status() != QueuedSend.Status.DONE
                && emAndamento.status() != QueuedSend.Status.FAILED) {
            saida.add(emAndamento);
        }
        saida.addAll(fila);
        return saida;
    }

    /**
     * Cancela um envio que ainda nao foi entregue ao WhatsApp.
     *
     * <p>Alcanca tambem o item que a fila <b>ja pegou</b> mas que esta apenas
     * esperando o intervalo ou publicando "digitando...". Isso importa na
     * pratica: com um intervalo de ate 12 segundos, um "parar" que so mexesse
     * na fila deixaria a proxima mensagem sair mesmo assim — que e justamente o
     * que o operador acabou de pedir para nao acontecer.
     *
     * <p>A partir do momento em que a mensagem vai para o bridge nao da mais:
     * ela pode ja ter chegado, e cancelar ali deixaria a duvida de se saiu.
     *
     * @param id o {@link QueuedSend#id()}
     * @return {@code true} se o cancelamento foi aceito
     */
    public boolean cancel(String id) {
        for (QueuedSend item : fila) {
            if (item.id().equals(id) && fila.remove(item)) {
                descartar(item, "cancelado na fila");
                return true;
            }
        }
        QueuedSend emAndamento = atual;
        if (emAndamento != null && emAndamento.id().equals(id) && emAndamento.podeCancelar()) {
            emAndamento.requestCancel();
            return true;
        }
        return false;
    }

    /**
     * Esvazia a fila, incluindo o item que esta apenas esperando a vez.
     *
     * <p>So um envio ja entregue ao bridge continua: abortar ali deixaria a
     * duvida de se a mensagem chegou.
     *
     * @return quantos envios foram descartados
     */
    public int clear() {
        int n = 0;
        QueuedSend item;
        while ((item = fila.pollFirst()) != null) {
            descartar(item, "fila esvaziada");
            n++;
        }
        QueuedSend emAndamento = atual;
        if (emAndamento != null && emAndamento.podeCancelar()) {
            emAndamento.requestCancel();
            n++;
        }
        return n;
    }

    /** Troca o ritmo em tempo de execucao. Vale do proximo item em diante. */
    public void setPacing(PacingOptions novo) {
        PacingOptions efetivo = novo != null ? novo : PacingOptions.padrao();
        this.pacing = efetivo;
        this.limite = new RateLimiter(efetivo.maxPerMinute());
    }

    /** Troca os sinais de humanizacao em tempo de execucao. */
    public void setHumanization(HumanizationOptions novo) {
        this.humanization = novo != null ? novo : HumanizationOptions.padrao();
    }

    public PacingOptions pacing() {
        return pacing;
    }

    public HumanizationOptions humanization() {
        return humanization;
    }

    @Override
    public void close() {
        if (!encerrado.compareAndSet(false, true)) {
            return;
        }
        clear();
        worker.interrupt();
    }

    // ---------------------------------------------------------------- laco

    /**
     * O laco da fila.
     *
     * <p><b>Captura {@code Throwable}, e nao {@code Exception}.</b> Esta e a
     * UNICA thread que envia por esta instancia: se ela morrer, a fila continua
     * aceitando mensagens e nenhuma sai mais — sem erro na aplicacao, sem nada
     * no log, so a fila crescendo. Um {@code Error} (tipicamente
     * {@code OutOfMemoryError}) escapava de um {@code catch (Exception)} e
     * encerraria o laco para sempre. Assim, o pior que um erro faz e derrubar
     * UMA mensagem.
     */
    private void rodar() {
        while (!encerrado.get()) {
            QueuedSend item;
            try {
                item = fila.takeFirst();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            atual = item;
            try {
                processar(item);
            } catch (Throwable e) {
                log.error("falha ao processar item da fila de '{}' - a fila continua rodando", instanceId, e);
                try {
                    concluir(item, null, e);
                } catch (Throwable ignorado) {
                    // Nem registrar a falha pode derrubar a fila.
                }
            } finally {
                atual = null;
            }
        }
    }

    private void processar(QueuedSend item) {
        PacingOptions ritmo = this.pacing;
        HumanizationOptions sinais = this.humanization;

        // 1. Espaco entre esta mensagem e a anterior.
        if (!esperarIntervalo(item, ritmo)) {
            concluir(item, null, new CancellationException("fila interrompida"));
            return;
        }
        if (cancelado(item)) {
            return;
        }

        // 2. Teto por minuto, contando tudo que sai por esta instancia.
        try {
            limite.aguardarVaga();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            concluir(item, null, new CancellationException("fila interrompida"));
            return;
        }
        if (cancelado(item)) {
            return;
        }

        // 3. Leu, depois respondeu: o azulzinho vai ANTES da resposta.
        if (sinais.readReceipts() == ReadReceiptMode.ON_REPLY) {
            marcarLidaAntesDeResponder(item, sinais);
        }

        // 4. "Digitando..."/"gravando audio..." pelo tempo da simulacao.
        item.marcar(QueuedSend.Status.TYPING);
        simularPresenca(item, sinais);
        // Ultima chance: depois daqui a mensagem vai para o WhatsApp e nao ha
        // mais como saber se ela chegou.
        if (cancelado(item)) {
            encerrarPresenca(item, sinais);
            return;
        }

        // 5. Envio de verdade.
        item.marcar(QueuedSend.Status.SENDING);
        String messageId = null;
        Throwable falha = null;
        try {
            CompletableFuture<String> f = item.envio().get();
            messageId = f.join();
        } catch (Throwable e) {
            falha = desembrulhar(e);
        }

        // 6. So envio confirmado gasta o intervalo humano. Se nada chegou a
        //    ninguem, nao ha por que a proxima tentativa esperar minutos: o
        //    espacamento existe para quem recebeu, nao para quem nao recebeu.
        if (falha == null) {
            ultimoEnvioMs = System.currentTimeMillis();
        }

        encerrarPresenca(item, sinais);
        concluir(item, messageId, falha);
    }

    /**
     * Espera o intervalo aleatorio desde o ultimo envio bem-sucedido.
     *
     * @return {@code false} se a thread foi interrompida
     */
    private boolean esperarIntervalo(QueuedSend item, PacingOptions ritmo) {
        if (ritmo.semEspera()) {
            return true;
        }
        long min = ritmo.minDelay().toMillis();
        long max = ritmo.maxDelay().toMillis();
        long alvo = (min == max) ? min : ThreadLocalRandom.current().nextLong(min, max + 1);
        long desde = (ultimoEnvioMs == 0) ? Long.MAX_VALUE : System.currentTimeMillis() - ultimoEnvioMs;
        long falta = alvo - desde;
        // `falta <= max` protege contra o primeiro envio (desde = MAX_VALUE,
        // falta negativo) e contra relogio que andou para tras.
        if (falta <= 0 || falta > max) {
            return true;
        }
        return dormirAtento(item, falta);
    }

    /**
     * Dorme em fatias, conferindo o pedido de cancelamento entre elas.
     *
     * <p>Um {@code Thread.sleep(12000)} unico tornaria o "parar" do operador
     * inutil justamente no intervalo em que ele mais importa: a mensagem sairia
     * assim mesmo, ate doze segundos depois de ele ter pedido para parar.
     *
     * @return {@code false} se a thread foi interrompida
     */
    private boolean dormirAtento(QueuedSend item, long totalMs) {
        long fim = System.currentTimeMillis() + totalMs;
        while (System.currentTimeMillis() < fim) {
            if (item.isCancelRequested()) {
                return true; // quem confere o cancelamento e o chamador
            }
            long fatia = Math.min(200L, fim - System.currentTimeMillis());
            if (fatia <= 0) {
                break;
            }
            try {
                Thread.sleep(fatia);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** Atende um cancelamento pedido, se houver. */
    private boolean cancelado(QueuedSend item) {
        if (!item.isCancelRequested()) {
            return false;
        }
        descartar(item, "cancelado antes de sair");
        return true;
    }

    private void marcarLidaAntesDeResponder(QueuedSend item, HumanizationOptions sinais) {
        Duration atraso = sinais.atrasoLeitura(ThreadLocalRandom.current());
        if (!atraso.isZero()) {
            try {
                Thread.sleep(atraso.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        try {
            backend.markChatRead(item.chatId()).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Nao ha mensagem conhecida na conversa, ou o WhatsApp recusou.
            // Marcar leitura e enfeite: nunca pode impedir a resposta de sair.
            log.debug("nao foi possivel marcar '{}' como lida antes de responder: {}",
                    item.chatId(), desembrulhar(e).getMessage());
        }
    }

    /**
     * Segura a mensagem pelo tempo da simulacao, publicando a presenca enquanto
     * isso.
     *
     * <p><b>A ESPERA NAO DEPENDE DA PRESENCA.</b> Se a publicacao falhar, a
     * pausa acontece do mesmo jeito. Voltar na hora seria o pior dos dois
     * mundos: sem o indicador E sem a pausa que faz o envio parecer humano.
     */
    private void simularPresenca(QueuedSend item, HumanizationOptions sinais) {
        boolean audio = item.kind() == QueuedSend.Kind.AUDIO;
        if (audio && !sinais.recording()) {
            return;
        }
        if (!audio && !sinais.typing()) {
            return;
        }

        Duration duracao = audio
                // Nao ha como saber de antemao quanto dura o audio sem abrir o
                // arquivo; alguns segundos e o que uma pessoa leva para gravar
                // um recado curto, e e o que importa aqui.
                ? Duration.ofMillis(ThreadLocalRandom.current().nextLong(
                        sinais.typingMin().toMillis(), Math.max(sinais.typingMin().toMillis() + 1,
                                sinais.typingMax().toMillis() + 1)))
                : sinais.duracaoDigitacao(item.textLength(), ThreadLocalRandom.current());

        if (duracao.isZero()) {
            return;
        }

        String presenca = audio ? "recording" : "composing";
        boolean publicou = publicarPresenca(item.chatId(), presenca);
        if (!publicou) {
            log.debug("presenca '{}' nao publicada em {} - a pausa acontece assim mesmo", presenca, item.chatId());
        }

        long fim = System.currentTimeMillis() + duracao.toMillis();
        while (System.currentTimeMillis() < fim) {
            long espera = Math.max(200L, Math.min(RENOVAR_PRESENCA_MS, fim - System.currentTimeMillis()));
            if (!dormirAtento(item, espera) || item.isCancelRequested()) {
                return;
            }
            if (publicou && System.currentTimeMillis() < fim) {
                publicou = publicarPresenca(item.chatId(), presenca);
            }
        }
    }

    /**
     * Encerra o indicador e devolve o numero ao estado offline.
     *
     * <p>O {@code unavailable} importa: para publicar a digitacao o bridge
     * precisa se declarar disponivel, e um numero que fica online 24 horas por
     * dia e justamente o padrao que identifica automacao. Ele so aparece online
     * nos segundos do envio.
     */
    private void encerrarPresenca(QueuedSend item, HumanizationOptions sinais) {
        boolean audio = item.kind() == QueuedSend.Kind.AUDIO;
        boolean publicouIndicador = audio ? sinais.recording() : sinais.typing();
        if (publicouIndicador) {
            publicarPresenca(item.chatId(), "paused");
        }
        if (!sinais.presenceAroundSend()) {
            return;
        }
        long segurarMs = sinais.onlineHoldAfterSend().toMillis();
        if (segurarMs > 0) {
            try {
                Thread.sleep(segurarMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        publicarPresenca(item.chatId(), "unavailable");
    }

    private boolean publicarPresenca(String chatId, String presenca) {
        try {
            backend.sendPresence(chatId, presenca).get(10, TimeUnit.SECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private void descartar(QueuedSend item, String motivo) {
        if (item.resultado().isDone()) {
            return;
        }
        item.marcar(QueuedSend.Status.CANCELLED);
        item.resultado().completeExceptionally(new CancellationException(motivo));
    }

    private void concluir(QueuedSend item, String messageId, Throwable falha) {
        if (item.resultado().isDone()) {
            return;
        }
        if (falha == null) {
            item.marcar(QueuedSend.Status.DONE);
            item.resultado().complete(messageId);
        } else {
            item.marcar(QueuedSend.Status.FAILED);
            item.resultado().completeExceptionally(falha);
        }
    }

    private static Throwable desembrulhar(Throwable t) {
        Throwable r = t;
        while ((r instanceof java.util.concurrent.ExecutionException
                || r instanceof java.util.concurrent.CompletionException) && r.getCause() != null) {
            r = r.getCause();
        }
        return r;
    }
}
