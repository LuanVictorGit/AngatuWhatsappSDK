package com.angatusistemas.whatsapp.pacing;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Teto de operacoes por minuto, em janela deslizante de 60 segundos.
 *
 * <p>E a segunda camada de protecao do {@link SendPacer}, ao lado do intervalo
 * aleatorio entre envios. As duas existem porque resolvem coisas diferentes: o
 * intervalo faz cada mensagem parecer digitada por gente, e o teto impede que
 * uma fila grande acumule pressa — sem ele, bastaria o intervalo cair no piso
 * algumas vezes seguidas para o numero emitir uma rajada.
 *
 * <p>Janela deslizante, e nao "contador que zera a cada minuto": um contador
 * por minuto cheio permite o dobro do teto na virada (tudo no fim de um minuto
 * mais tudo no inicio do seguinte), que e exatamente a rajada que se quer
 * evitar.
 *
 * <p>Thread-safe. Pensado para baixa concorrencia — poucas threads disputando —,
 * nao para alto volume.
 */
public final class RateLimiter {

    private static final long JANELA_MS = 60_000L;

    private final int maxPorMinuto;
    private final Deque<Long> marcas = new ArrayDeque<>();

    /**
     * @param maxPorMinuto teto de operacoes por minuto (valores abaixo de 1 sao
     *        elevados a 1 — um teto zero travaria a fila para sempre)
     */
    public RateLimiter(int maxPorMinuto) {
        this.maxPorMinuto = Math.max(1, maxPorMinuto);
    }

    /** @return o teto configurado */
    public int maxPorMinuto() {
        return maxPorMinuto;
    }

    /**
     * Bloqueia ate haver vaga na janela e entao registra esta operacao.
     *
     * <p>Chamar imediatamente antes da operacao real: registrar a marca aqui (e
     * nao depois) e o que impede duas threads de passarem juntas pela ultima
     * vaga.
     *
     * @throws InterruptedException se a thread for interrompida durante a espera
     */
    public void aguardarVaga() throws InterruptedException {
        while (true) {
            long esperaMs;
            synchronized (this) {
                long agora = System.currentTimeMillis();
                descartarAntigas(agora);
                if (marcas.size() < maxPorMinuto) {
                    marcas.addLast(agora);
                    return;
                }
                // +50ms: acordar no instante exato em que a marca mais antiga
                // vence devolveria o laco sem vaga, gastando uma volta a toa.
                esperaMs = JANELA_MS - (agora - marcas.peekFirst()) + 50;
            }
            if (esperaMs > 0) {
                Thread.sleep(esperaMs);
            }
        }
    }

    /**
     * Toma uma vaga se houver uma agora; nunca espera.
     *
     * <p>Para os caminhos em que segurar a thread seria pior do que desistir —
     * uma consulta que tem alternativa boa, por exemplo. Para envio, use
     * {@link #aguardarVaga()}: pular a vez ali significaria nao entregar a
     * mensagem.
     *
     * @return {@code true} se a operacao pode acontecer agora
     */
    public synchronized boolean tentarVaga() {
        long agora = System.currentTimeMillis();
        descartarAntigas(agora);
        if (marcas.size() >= maxPorMinuto) {
            return false;
        }
        marcas.addLast(agora);
        return true;
    }

    /** Quantas operacoes ainda cabem na janela neste instante. */
    public synchronized int vagasDisponiveis() {
        descartarAntigas(System.currentTimeMillis());
        return Math.max(0, maxPorMinuto - marcas.size());
    }

    private void descartarAntigas(long agora) {
        while (!marcas.isEmpty() && agora - marcas.peekFirst() >= JANELA_MS) {
            marcas.pollFirst();
        }
    }
}
