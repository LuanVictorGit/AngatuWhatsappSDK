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
 * servidor nega e o telefone diz apenas "nao foi possivel conectar". Nada
 * quebra no nosso lado; a conexao simplesmente para de funcionar sozinha um
 * dia, sem mudanca de codigo.
 *
 * Por isso a versao e perguntada ao proprio WhatsApp na hora de abrir o
 * socket, e nao lida de uma constante.
 *
 * @author Angatu Sistemas
 */

/** Enquanto o valor em cache for mais novo que isso, ninguem vai a rede. */
const VALIDADE_MS = 6 * 60 * 60 * 1000;

/** Teto para a consulta: servidor sem internet nao pode travar o connect(). */
const TIMEOUT_MS = 8_000;

let cache: { versao: WAVersion; obtidaEm: number } | null = null;

/** Consulta em voo, para varias instancias conectando juntas irem a rede uma vez so. */
let emVoo: Promise<WAVersion> | null = null;

/**
 * Versao do WhatsApp Web a usar no socket.
 *
 * Nunca lanca: sem internet, o pior caso e voltar a versao empacotada — que e
 * exatamente o comportamento que o Baileys teria sozinho.
 *
 * @param logger para registrar qual versao acabou valendo
 * @return a versao mais nova que conseguimos descobrir
 */
export async function resolverVersaoWhatsApp(logger: Logger): Promise<WAVersion> {
    if (cache && Date.now() - cache.obtidaEm < VALIDADE_MS) {
        return cache.versao;
    }
    emVoo ??= consultar(logger).finally(() => {
        emVoo = null;
    });
    return emVoo;
}

/** Esquece o valor em cache (usado pelos testes). */
export function limparCacheDeVersao(): void {
    cache = null;
}

async function consultar(logger: Logger): Promise<WAVersion> {
    // `fetchLatestWaWebVersion` le o service worker do web.whatsapp.com — e a
    // fonte primaria, o numero que o proprio WhatsApp esta servindo agora.
    // Ela nunca lanca: em caso de falha volta a versao empacotada com
    // `isLatest: false`, e ai vale a pena tentar o arquivo do repositorio do
    // Baileys, que costuma estar mais novo que o pacote instalado.
    const doWhatsApp = await fetchLatestWaWebVersion({ timeout: TIMEOUT_MS });
    if (doWhatsApp.isLatest) {
        return guardar(doWhatsApp.version, 'web.whatsapp.com', logger);
    }

    const doBaileys = await fetchLatestBaileysVersion({ timeout: TIMEOUT_MS });
    if (doBaileys.isLatest) {
        return guardar(doBaileys.version, 'repositorio do Baileys', logger);
    }

    // Offline: segue com a versao do pacote e NAO guarda em cache, para a
    // proxima reconexao tentar de novo assim que a rede voltar.
    logger.warn(
        { versao: doBaileys.version.join('.') },
        'nao consegui descobrir a versao do WhatsApp Web; usando a versao empacotada — se o pareamento falhar, e por causa disso'
    );
    return doBaileys.version;
}

function guardar(versao: WAVersion, fonte: string, logger: Logger): WAVersion {
    cache = { versao, obtidaEm: Date.now() };
    logger.info({ versao: versao.join('.'), fonte }, 'versao do WhatsApp Web resolvida');
    return versao;
}
