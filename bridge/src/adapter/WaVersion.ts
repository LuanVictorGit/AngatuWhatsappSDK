import { fetchLatestWaWebVersion, fetchLatestBaileysVersion } from 'baileys';
import type { WAVersion } from 'baileys';
import type { Logger } from '../util/logger.js';

/**
 * Descobre qual versao do WhatsApp Web anunciar no handshake.
 *
 * O Baileys traz uma versao **congelada** no proprio pacote
 * (`Defaults/baileys-version.json`). Ela envelhece junto com a dependencia, e
 * o WhatsApp recusa o pareamento de cliente velho — com um sintoma que engana:
 * o QR Code e o codigo de pareamento sao gerados localmente e aparecem
 * normalmente na tela, mas quando o celular tenta concluir o vinculo o
 * servidor nega e o telefone diz apenas "nao foi possivel conectar".
 *
 * **Esta consulta nunca pode quebrar a conexao.** Ela roda antes do
 * `makeWASocket`, entao qualquer erro aqui vira "nao aparece QR nem codigo" —
 * um estrago muito maior que o problema que ela resolve. Por isso:
 *
 * - o retorno e `null` quando nao deu para descobrir, e quem chama **omite** a
 *   chave `version`. Passar `undefined` para o `makeWASocket` seria pior que
 *   nao passar nada: o spread das opcoes **sobrescreve** o padrao do Baileys,
 *   e o socket sobe sem versao nenhuma;
 * - o que volta da rede e **conferido** antes de virar versao. As duas funcoes
 *   do Baileys devolvem `isLatest: true` sem olhar o corpo da resposta, entao
 *   um proxy ou portal de rede que responda 200 com HTML faz `version` chegar
 *   como `undefined`;
 * - ha um teto de tempo para o conjunto: rede lenta atrasa a tela, e o
 *   operador esta olhando para um quadro vazio enquanto isso.
 *
 * @author Angatu Sistemas
 */

/** Enquanto o valor em cache for mais novo que isso, ninguem vai a rede. */
const VALIDADE_MS = 6 * 60 * 60 * 1000;

/** Teto por consulta. */
const TIMEOUT_MS = 4_000;

/** Teto do conjunto: passou disso, conecta com a versao do pacote mesmo. */
const TIMEOUT_TOTAL_MS = 9_000;

let cache: { versao: WAVersion; obtidaEm: number } | null = null;

/** Consulta em voo, para varias instancias conectando juntas irem a rede uma vez so. */
let emVoo: Promise<WAVersion | null> | null = null;

/**
 * Versao do WhatsApp Web a usar no socket.
 *
 * @param logger para registrar qual versao acabou valendo
 * @return a versao descoberta, ou `null` para o Baileys usar a dele
 */
export async function resolverVersaoWhatsApp(logger: Logger): Promise<WAVersion | null> {
    try {
        // Valvula de escape: se um dia esta consulta virar problema, da para
        // desligar no .env sem recompilar nada.
        if (process.env.ANGATU_WA_VERSION_OFF === '1') {
            return null;
        }
        if (cache && Date.now() - cache.obtidaEm < VALIDADE_MS) {
            return cache.versao;
        }
        emVoo ??= consultar(logger).finally(() => {
            emVoo = null;
        });
        return await Promise.race([emVoo, esgotar(TIMEOUT_TOTAL_MS)]);
    } catch (err) {
        // Rede, DNS, proxy, o que for: conectar com a versao do pacote e
        // infinitamente melhor que nao conectar.
        logger.warn({ err }, 'falha ao descobrir a versao do WhatsApp Web; seguindo com a do pacote');
        return null;
    }
}

/** Esquece o valor em cache (usado pelos testes). */
export function limparCacheDeVersao(): void {
    cache = null;
    emVoo = null;
}

/**
 * Confere que o que voltou da rede e mesmo uma versao.
 *
 * As duas funcoes do Baileys marcam `isLatest: true` sem olhar o corpo da
 * resposta, entao HTML de portal de rede chega aqui como `undefined`.
 */
export function versaoValida(v: unknown): v is WAVersion {
    return (
        Array.isArray(v) &&
        v.length === 3 &&
        v.every((n) => typeof n === 'number' && Number.isInteger(n) && n >= 0)
    );
}

async function consultar(logger: Logger): Promise<WAVersion | null> {
    // Fonte primaria: o service worker do web.whatsapp.com, que e o numero que
    // o proprio WhatsApp esta servindo agora.
    const doWhatsApp = await fetchLatestWaWebVersion({ timeout: TIMEOUT_MS }).catch(() => null);
    if (doWhatsApp?.isLatest && versaoValida(doWhatsApp.version)) {
        return guardar(doWhatsApp.version, 'web.whatsapp.com', logger);
    }

    // O arquivo no repositorio do Baileys costuma estar mais novo que o pacote
    // instalado — vale a tentativa antes de desistir.
    const doBaileys = await fetchLatestBaileysVersion({ timeout: TIMEOUT_MS }).catch(() => null);
    if (doBaileys?.isLatest && versaoValida(doBaileys.version)) {
        return guardar(doBaileys.version, 'repositorio do Baileys', logger);
    }

    // Offline ou resposta estranha: segue com a versao do pacote e NAO guarda
    // em cache, para a proxima reconexao tentar de novo quando a rede voltar.
    logger.warn(
        'nao consegui descobrir a versao do WhatsApp Web; usando a do pacote — se o pareamento falhar, e por causa disso'
    );
    return null;
}

function guardar(versao: WAVersion, fonte: string, logger: Logger): WAVersion {
    cache = { versao, obtidaEm: Date.now() };
    logger.info({ versao: versao.join('.'), fonte }, 'versao do WhatsApp Web resolvida');
    return versao;
}

function esgotar(ms: number): Promise<null> {
    return new Promise((resolve) => {
        const t = setTimeout(() => resolve(null), ms);
        // Nao segura o processo vivo so por causa deste temporizador.
        t.unref?.();
    });
}
