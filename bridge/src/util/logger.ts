// Named import (not default): pino's CJS->ESM default-export interop under
// "moduleResolution": "NodeNext" resolves to a non-callable namespace type
// with a plain default import. pino explicitly publishes `pino` as a named
// export too, which types and bundles correctly.
import { pino, type LogFn } from 'pino';

/**
 * Structured JSON logger written synchronously to stdout (one line per
 * record). The Java side's BridgeLogForwarder reads this stream line by
 * line and re-emits it through SLF4J, so the shape here (level, time,
 * msg, plus bindings) is deliberately kept pino-default rather than
 * customized - it is machine-parsed on the other end.
 *
 * No `transport` is configured on purpose: pino transports run in a
 * worker thread loaded via a dynamic path that does not survive being
 * bundled into a single file by esbuild.
 */
const level = process.env.ANGATU_BRIDGE_LOG_LEVEL ?? 'info';

export const rootLogger = pino({
    level,
    // Never let the WebSocket auth token, or full session credentials,
    // reach a log line even if a caller accidentally logs an object
    // that contains one of these keys.
    redact: {
        paths: ['token', '*.token', 'creds', '*.creds', 'authState', '*.authState'],
        censor: '[redacted]'
    },
    timestamp: pino.stdTimeFunctions.isoTime
});

export function childLogger(bindings: Record<string, unknown>) {
    return rootLogger.child(bindings);
}

export type Logger = ReturnType<typeof childLogger>;

/**
 * Mensagens que o Baileys registra como ERRO e que sao, na verdade, o
 * protocolo funcionando.
 *
 * `failed to decrypt message` numa conversa de grupo significa que chegou uma
 * mensagem cifrada por um aparelho com o qual ainda nao temos sessao Signal —
 * situacao normal para um aparelho vinculado depois, e para participantes que
 * entraram no grupo antes de nos. O Baileys responde com um `sent retry
 * receipt`, pedindo ao remetente que reenvie cifrado para nos; se ele
 * responder, a mensagem chega, e se nao responder, nao ha nada a fazer do
 * nosso lado.
 *
 * O problema pratico e o volume: em um grupo movimentado isso enche o
 * journalctl com paginas de stack trace por hora e enterra o log de envio, que
 * e o que se precisa ler quando algo da errado de verdade. Aqui cada uma
 * aparece UMA vez, com a explicacao, e as repeticoes caem para `debug` — quem
 * quiser ve-las liga `ANGATU_BRIDGE_LOG_LEVEL=debug`.
 */
const RUIDO_DO_PROTOCOLO: ReadonlyArray<{ trecho: string; explicacao: string }> = [
    {
        trecho: 'failed to decrypt message',
        explicacao:
            'mensagem de grupo que nao pudemos decifrar (sem sessao Signal com o aparelho que enviou). ' +
            'O Baileys ja pediu o reenvio ao remetente; e comportamento normal do protocolo, nao falha da ' +
            'aplicacao. As proximas ocorrencias ficam em nivel debug (ANGATU_BRIDGE_LOG_LEVEL=debug para ve-las).'
    },
    {
        trecho: 'sent retry receipt',
        explicacao:
            'pedido de reenvio mandado ao remetente de uma mensagem que nao pudemos decifrar. ' +
            'As proximas ocorrencias ficam em nivel debug.'
    }
];

/** {@code true} quando o nivel configurado ja mostra tudo. */
const MOSTRA_TUDO = level === 'debug' || level === 'trace';

/**
 * Quantas vezes cada ruido de protocolo apareceu, por instancia.
 *
 * <p><b>Por que contar o que se resolveu esconder.</b> Silenciar as repeticoes
 * resolveu o log, mas apagou um sinal: uma <i>escalada</i> de falhas de decrypt
 * e o aviso precoce de que a sessao Signal esta se degradando — e, se o numero
 * passa a pedir reenvio o tempo todo, isso conta contra a reputacao dele. Sem
 * contador, essa escalada ficava invisivel no nivel padrao. O log continua
 * limpo; o numero fica disponivel em `get_status`.
 */
const contadoresDeRuido = new Map<string, Map<string, number>>();

/**
 * Quantas vezes cada ruido de protocolo apareceu numa instancia.
 *
 * @param instanceId instancia
 * @return mapa trecho -> contagem (vazio se nada aconteceu)
 */
export function ruidoDoProtocolo(instanceId: string): Record<string, number> {
    const contagens = contadoresDeRuido.get(instanceId);
    return contagens ? Object.fromEntries(contagens) : {};
}

function contarRuido(instanceId: string, trecho: string): number {
    let contagens = contadoresDeRuido.get(instanceId);
    if (!contagens) {
        contagens = new Map();
        contadoresDeRuido.set(instanceId, contagens);
    }
    const total = (contagens.get(trecho) ?? 0) + 1;
    contagens.set(trecho, total);
    return total;
}

/**
 * Logger entregue ao Baileys, com o ruido de protocolo resumido.
 *
 * Instancia separada (e nao um child do rootLogger) porque o filtro vive no
 * `hooks.logMethod`, que so existe na raiz. Escreve no mesmo stdout, no mesmo
 * formato — o BridgeLogForwarder do lado Java nao percebe diferenca.
 *
 * @param instanceId instancia a que este logger pertence
 */
export function loggerDoBaileys(instanceId: string) {
    const jaExplicado = new Set<string>();
    return pino({
        level,
        redact: {
            paths: ['token', '*.token', 'creds', '*.creds', 'authState', '*.authState'],
            censor: '[redacted]'
        },
        timestamp: pino.stdTimeFunctions.isoTime,
        hooks: {
            logMethod(args: [msg: string, ...rest: unknown[]], method: LogFn) {
                if (MOSTRA_TUDO) {
                    method.apply(this, args);
                    return;
                }
                // pino aceita (msg) e (obj, msg): a mensagem esta no primeiro
                // argumento que for string.
                const texto = (args as unknown[]).find((a) => typeof a === 'string') as string | undefined;
                const ruido = texto
                    ? RUIDO_DO_PROTOCOLO.find((r) => texto.includes(r.trecho))
                    : undefined;
                if (!ruido) {
                    method.apply(this, args);
                    return;
                }
                const total = contarRuido(instanceId, ruido.trecho);
                if (!jaExplicado.has(ruido.trecho)) {
                    jaExplicado.add(ruido.trecho);
                    method.apply(this, [ruido.explicacao]);
                }
                // Repeticoes: descartadas do log, mas contadas. Uma escalada
                // dessas e o aviso precoce de sessao Signal degradando, e sem
                // marco nenhum ela passaria despercebida ate o numero cair.
                else if (total % 500 === 0) {
                    method.apply(this, [
                        { ocorrencias: total, trecho: ruido.trecho },
                        'ruido de protocolo se repetindo muito - confira get_status se o numero estiver instavel'
                    ] as never);
                }
            }
        }
    }).child({ instanceId, componente: 'baileys' });
}
