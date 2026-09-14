import { BridgeCommandError, ErrorCode } from '../protocol/errors.js';

/** Teto padrao de consultas por minuto. `ANGATU_BRIDGE_LEITURAS_POR_MIN=0` desliga. */
const PADRAO_POR_MINUTO = 30;

const JANELA_MS = 60_000;

function tetoConfigurado(): number {
    const bruto = process.env.ANGATU_BRIDGE_LEITURAS_POR_MIN;
    if (bruto === undefined) {
        return PADRAO_POR_MINUTO;
    }
    const n = Number.parseInt(bruto, 10);
    return Number.isFinite(n) && n >= 0 ? n : PADRAO_POR_MINUTO;
}

/**
 * Teto de <b>consultas</b> ao WhatsApp por minuto, em janela deslizante.
 *
 * <p><b>Por que existe.</b> O que suspendeu a conta que originou este trabalho
 * nao foi envio: foram leituras. Uma tela que pedia o avatar de 200 conversas
 * ao abrir disparava, via `getContact`, ate 600 consultas em rajada logo apos
 * conectar — sondando existencia de numero e lendo o recado pessoal de gente com
 * quem ninguem estava conversando. Do ponto de vista do servidor do WhatsApp
 * isso e indistinguivel de raspagem de agenda.
 *
 * <p>A protecao de fundo e a aplicacao nao pedir o que nao exibe, e isso a SDK
 * nao tem como garantir. O que ela pode garantir e que <b>nenhuma sequencia de
 * chamadas vira rajada</b>: estourado o teto, a consulta e recusada com um erro
 * tipado, em vez de virar mais uma requisicao ao WhatsApp.
 *
 * <p>Recusar e melhor que enfileirar: uma consulta que espera trinta segundos
 * escondida ja perdeu o sentido para quem a pediu, e a fila cresceria sem que
 * ninguem percebesse que a aplicacao esta lendo demais. O erro e visivel e diz
 * o que fazer.
 */
export class ReadBudget {
    private readonly marcas: number[] = [];
    private readonly maxPorMinuto: number;

    constructor(maxPorMinuto = tetoConfigurado()) {
        this.maxPorMinuto = maxPorMinuto;
    }

    /**
     * Registra uma consulta, ou recusa se o teto ja foi atingido.
     *
     * @param operacao nome da consulta, para a mensagem de erro
     * @throws BridgeCommandError quando o teto do minuto foi estourado
     */
    consumir(operacao: string): void {
        if (this.maxPorMinuto === 0) {
            return;
        }
        const agora = Date.now();
        while (this.marcas.length > 0 && agora - this.marcas[0]! >= JANELA_MS) {
            this.marcas.shift();
        }
        if (this.marcas.length >= this.maxPorMinuto) {
            const esperaS = Math.ceil((JANELA_MS - (agora - this.marcas[0]!)) / 1000);
            throw new BridgeCommandError(
                ErrorCode.TIMEOUT,
                `Teto de consultas ao WhatsApp atingido (${this.maxPorMinuto}/min) ao executar '${operacao}'.` +
                    ` Tente de novo em ${esperaS}s. Consultar em rajada e o comportamento que leva a conta a ser` +
                    ` suspensa por coleta de dados - prefira exibir dado ja guardado localmente e consultar o` +
                    ` WhatsApp so quando o usuario pedir. Ajuste com ANGATU_BRIDGE_LEITURAS_POR_MIN.`,
                { operacao, maxPorMinuto: this.maxPorMinuto, tenteEmSegundos: esperaS }
            );
        }
        this.marcas.push(agora);
    }

    /** Quantas consultas ainda cabem neste minuto (Infinity se desligado). */
    get disponiveis(): number {
        if (this.maxPorMinuto === 0) {
            return Number.POSITIVE_INFINITY;
        }
        const agora = Date.now();
        const vivas = this.marcas.filter((m) => agora - m < JANELA_MS).length;
        return Math.max(0, this.maxPorMinuto - vivas);
    }
}
