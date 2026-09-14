import { open, mkdir, readFile, readdir, rename, rm, unlink, stat } from 'node:fs/promises';
import { join } from 'node:path';
import {
    initAuthCreds,
    BufferJSON,
    proto,
    type AuthenticationCreds,
    type AuthenticationState,
    type SignalDataTypeMap
} from 'baileys';
import type { Logger } from '../util/logger.js';

/** Arquivos do diretorio de sessao que NAO sao credencial e nunca devem ser apagados. */
const NAO_E_CREDENCIAL = new Set(['.lock']);

/** Sufixo do arquivo temporario usado na gravacao atomica. */
const TMP = '.tmp';

export interface DurableAuthState {
    state: AuthenticationState;
    saveCreds: () => Promise<void>;
}

/**
 * Estado de autenticacao do Baileys com gravacao <b>duravel</b>.
 *
 * <p><b>Por que nao o `useMultiFileAuthState` do pacote.</b> Ele grava com um
 * `writeFile(caminho, JSON.stringify(...))` direto por cima do arquivo final —
 * sem temporario, sem `rename`, sem `fsync`. Uma queda no meio dessa escrita
 * (OOM, `docker stop`, falta de energia, disco cheio) deixa o `creds.json`
 * truncado. E o pior nao e a perda: o `readData` do proprio Baileys engole o
 * erro de parse e devolve `null`, o que faz o `initAuthCreds()` fabricar uma
 * <b>identidade nova</b>. O sintoma que chega ao operador e "pediu QR do nada",
 * sem nenhum erro em lugar nenhum.
 *
 * <p>Aqui cada arquivo e escrito num `.tmp`, sincronizado no disco com `fsync`
 * e so entao movido por cima do definitivo com `rename` — que e atomico no
 * sistema de arquivos. O arquivo final ou tem o conteudo velho inteiro ou o
 * novo inteiro; truncado, nunca.
 *
 * <p>O formato em disco e <b>identico</b> ao do Baileys (mesmos nomes de
 * arquivo, mesmo JSON com `BufferJSON`). Isso e requisito, nao coincidencia:
 * sessoes ja pareadas precisam continuar valendo depois desta mudanca, e
 * projetos que copiam o diretorio de um lugar para outro (ver a migracao de
 * credenciais do AngatuZap) nao podem quebrar.
 */
export async function useDurableAuthState(folder: string, logger: Logger): Promise<DurableAuthState> {
    const info = await stat(folder).catch(() => null);
    if (info && !info.isDirectory()) {
        throw new Error(`existe algo que nao e diretorio em ${folder} - apague ou escolha outro local`);
    }
    if (!info) {
        await mkdir(folder, { recursive: true });
    }

    const travas = new Map<string, Promise<unknown>>();

    /**
     * Serializa as operacoes sobre um mesmo arquivo.
     *
     * O Baileys usa `async-mutex` para isto; aqui a fila de promessas resolve o
     * mesmo problema sem herdar uma dependencia transitiva. Vale so dentro deste
     * processo — a exclusao entre processos e a `SessionLock`.
     */
    const comTrava = <T>(caminho: string, tarefa: () => Promise<T>): Promise<T> => {
        const anterior = travas.get(caminho) ?? Promise.resolve();
        // `then(tarefa, tarefa)`: a proxima operacao no arquivo roda mesmo que a
        // anterior tenha falhado. Sem isso, um unico erro de disco travaria
        // aquele arquivo para sempre.
        const atual = anterior.then(tarefa, tarefa);
        const encadeada = atual.then(
            () => undefined,
            () => undefined
        );
        travas.set(caminho, encadeada);
        // Solta a entrada quando ela e a ultima da fila daquele arquivo: sem
        // isto o mapa guardaria uma promessa por chave de sessao ja gravada, e
        // uma conta ativa gera milhares delas.
        void encadeada.then(() => {
            if (travas.get(caminho) === encadeada) {
                travas.delete(caminho);
            }
        });
        return atual;
    };

    const nomeSeguro = (file: string): string => file?.replace(/\//g, '__')?.replace(/:/g, '-');

    const gravar = async (dados: unknown, file: string): Promise<void> => {
        const caminho = join(folder, nomeSeguro(file));
        await comTrava(caminho, async () => {
            const temporario = caminho + TMP;
            const conteudo = JSON.stringify(dados, BufferJSON.replacer);
            const fd = await open(temporario, 'w');
            try {
                await fd.writeFile(conteudo, 'utf8');
                // O rename so e util se os bytes ja estiverem no disco: sem o
                // fsync, uma queda logo apos o rename deixa um arquivo novo
                // vazio no lugar do antigo, que e pior do que nao ter gravado.
                await fd.sync();
            } finally {
                await fd.close();
            }
            await rename(temporario, caminho);
        });
    };

    const ler = async (file: string): Promise<unknown> => {
        const caminho = join(folder, nomeSeguro(file));
        return comTrava(caminho, async () => {
            try {
                return JSON.parse(await readFile(caminho, { encoding: 'utf-8' }), BufferJSON.reviver);
            } catch (err) {
                const codigo = (err as NodeJS.ErrnoException).code;
                if (codigo !== 'ENOENT') {
                    // Um arquivo ilegivel que NAO e ausencia merece registro: e o
                    // rastro de uma corrupcao, e sem ele a unica pista que sobra e
                    // o QR reaparecendo sem motivo.
                    logger.error({ err, arquivo: nomeSeguro(file) }, 'arquivo de autenticacao ilegivel - tratado como ausente');
                }
                return null;
            }
        });
    };

    const remover = async (file: string): Promise<void> => {
        const caminho = join(folder, nomeSeguro(file));
        await comTrava(caminho, async () => {
            await unlink(caminho).catch(() => undefined);
        });
    };

    await limparTemporarios(folder, logger);

    const creds: AuthenticationCreds = ((await ler('creds.json')) as AuthenticationCreds | null) ?? initAuthCreds();

    return {
        state: {
            creds,
            keys: {
                get: async (type, ids) => {
                    const data: { [id: string]: SignalDataTypeMap[typeof type] } = {};
                    await Promise.all(
                        ids.map(async (id) => {
                            let value = await ler(`${type}-${id}.json`);
                            if (type === 'app-state-sync-key' && value) {
                                value = proto.Message.AppStateSyncKeyData.fromObject(value as never);
                            }
                            data[id] = value as SignalDataTypeMap[typeof type];
                        })
                    );
                    return data;
                },
                set: async (data) => {
                    const tarefas: Promise<void>[] = [];
                    for (const categoria in data) {
                        const grupo = data[categoria as keyof typeof data];
                        for (const id in grupo) {
                            const valor = (grupo as Record<string, unknown>)[id];
                            const arquivo = `${categoria}-${id}.json`;
                            tarefas.push(valor ? gravar(valor, arquivo) : remover(arquivo));
                        }
                    }
                    await Promise.all(tarefas);
                }
            }
        },
        saveCreds: async () => gravar(creds, 'creds.json')
    };
}

/**
 * Apaga `.tmp` que sobraram de uma queda no meio de uma gravacao.
 *
 * Nao ha nada a recuperar deles (ou o `rename` aconteceu, e o definitivo esta
 * bom, ou nao aconteceu, e o temporario esta pela metade); deixa-los so faria o
 * diretorio crescer.
 */
async function limparTemporarios(folder: string, logger: Logger): Promise<void> {
    try {
        const arquivos = await readdir(folder);
        const sobras = arquivos.filter((nome) => nome.endsWith(TMP));
        for (const nome of sobras) {
            await unlink(join(folder, nome)).catch(() => undefined);
        }
        if (sobras.length > 0) {
            logger.warn({ quantidade: sobras.length }, 'restos de gravacao interrompida removidos do diretorio de sessao');
        }
    } catch {
        // diretorio recem-criado ou inacessivel: nao ha sobra a limpar
    }
}

/**
 * Apaga as credenciais de um diretorio de sessao <b>preservando o diretorio e a
 * trava</b>.
 *
 * <p>Substitui o `rm -rf` que existia aqui, e a diferenca nao e cosmetica: o
 * diretorio guarda tambem o `.lock` da {@link SessionLock}, e apagar a pasta
 * inteira jogava fora a trava que este processo ainda detem — abrindo espaco
 * para outro processo tomar a conta no exato momento em que ela esta sendo
 * repareada.
 */
export async function limparCredenciais(folder: string, logger: Logger): Promise<void> {
    let arquivos: string[];
    try {
        arquivos = await readdir(folder);
    } catch (err) {
        if ((err as NodeJS.ErrnoException).code !== 'ENOENT') {
            logger.error({ err, folder }, 'falha ao listar o diretorio de sessao para limpeza');
        }
        return;
    }
    let apagados = 0;
    for (const nome of arquivos) {
        if (NAO_E_CREDENCIAL.has(nome)) {
            continue;
        }
        const caminho = join(folder, nome);
        try {
            // `recursive` porque o diretorio de sessao tambem guarda a pasta
            // `sent-messages` (as mensagens que enviamos, para atender pedido de
            // reenvio). Elas sao conteudo de uma sessao que esta sendo
            // descartada: deixa-las para tras guardaria mensagem de uma conta
            // que nao esta mais conectada.
            await rm(caminho, { recursive: true, force: true });
            apagados++;
        } catch (err) {
            logger.warn({ err, arquivo: nome }, 'nao foi possivel apagar um arquivo de credencial');
        }
    }
    logger.info({ apagados }, 'credenciais da sessao removidas (trava e diretorio preservados)');
}
