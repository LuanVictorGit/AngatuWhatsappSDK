package com.angatusistemas.whatsapp.pacing;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Um envio esperando a vez na fila de uma instancia.
 *
 * <p>Devolvido por {@code WhatsAppClient.pendingSends()} para a aplicacao poder
 * mostrar o que esta saindo e cancelar o que ainda nao saiu.
 */
public final class QueuedSend {

    /** Em que ponto do caminho o envio esta. */
    public enum Status {
        /** Na fila, ainda nao comecou. Unico estado em que da para cancelar. */
        QUEUED,
        /** Publicando "digitando..."/"gravando..." e segurando pelo tempo da simulacao. */
        TYPING,
        /** Entregue ao bridge, esperando o WhatsApp confirmar. */
        SENDING,
        /** O WhatsApp aceitou; o id da mensagem esta no future. */
        DONE,
        /** Falhou; o motivo esta no future. */
        FAILED,
        /** Cancelado antes de comecar. */
        CANCELLED
    }

    /** Que tipo de sinal de presenca este envio merece. */
    public enum Kind {
        /** Texto: "digitando...", com duracao proporcional ao tamanho. */
        TEXT,
        /** Midia com ou sem legenda: "digitando...", pela legenda. */
        MEDIA,
        /** Audio/PTT: "gravando audio...". */
        AUDIO
    }

    private final String id;
    private final String chatId;
    private final Kind kind;
    private final int textLength;
    private final Supplier<CompletableFuture<String>> envio;
    private final CompletableFuture<String> resultado = new CompletableFuture<>();
    private final long enqueuedAtMs = System.currentTimeMillis();

    private volatile Status status = Status.QUEUED;
    private volatile long startedAtMs;
    private volatile long finishedAtMs;
    private volatile boolean cancelRequested;

    QueuedSend(String id, String chatId, Kind kind, int textLength, Supplier<CompletableFuture<String>> envio) {
        this.id = id;
        this.chatId = chatId;
        this.kind = kind;
        this.textLength = textLength;
        this.envio = envio;
    }

    /** Identificador do item na fila, usado para cancelar. */
    public String id() {
        return id;
    }

    /** Destino do envio. */
    public String chatId() {
        return chatId;
    }

    public Kind kind() {
        return kind;
    }

    public Status status() {
        return status;
    }

    /** Quando o item entrou na fila (epoch ms). */
    public long enqueuedAtMs() {
        return enqueuedAtMs;
    }

    /** Quando a fila comecou a processa-lo, ou 0 se ainda nao comecou. */
    public long startedAtMs() {
        return startedAtMs;
    }

    /** Quando terminou, ou 0 se ainda nao terminou. */
    public long finishedAtMs() {
        return finishedAtMs;
    }

    /**
     * O resultado do envio: o id da mensagem, ou a falha.
     *
     * <p>E o mesmo future que os metodos {@code send*} do
     * {@code WhatsAppClient} devolvem.
     */
    public CompletableFuture<String> result() {
        return resultado;
    }

    /**
     * Pede o cancelamento de um item que a fila ja pegou mas ainda nao entregou
     * ao WhatsApp.
     *
     * <p>Quem cancela nao completa o item: a thread da fila e que percebe o
     * pedido na proxima conferencia e o encerra. Isso mantem a conclusao num
     * lugar so, em vez de duas threads disputando o mesmo future.
     */
    void requestCancel() {
        this.cancelRequested = true;
    }

    /** Se ha um cancelamento pedido e ainda nao atendido. */
    boolean isCancelRequested() {
        return cancelRequested;
    }

    /**
     * Se o item ainda pode ser cancelado: ele nao foi entregue ao bridge.
     *
     * <p>A partir de {@link Status#SENDING} nao da mais: a mensagem pode ja ter
     * chegado, e cancelar ali deixaria a duvida de se ela saiu ou nao.
     */
    boolean podeCancelar() {
        return status == Status.QUEUED || status == Status.TYPING;
    }

    int textLength() {
        return textLength;
    }

    Supplier<CompletableFuture<String>> envio() {
        return envio;
    }

    CompletableFuture<String> resultado() {
        return resultado;
    }

    void marcar(Status novo) {
        this.status = novo;
        if (novo == Status.TYPING || novo == Status.SENDING) {
            if (startedAtMs == 0) {
                startedAtMs = System.currentTimeMillis();
            }
        } else if (novo == Status.DONE || novo == Status.FAILED || novo == Status.CANCELLED) {
            finishedAtMs = System.currentTimeMillis();
        }
    }

    @Override
    public String toString() {
        return "QueuedSend[" + id + " -> " + chatId + " (" + kind + ") " + status + "]";
    }
}
