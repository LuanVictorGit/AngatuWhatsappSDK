import { childLogger } from './util/logger.js';
import { readExpectedToken } from './server/AuthToken.js';
import { BridgeServer } from './server/WebSocketServer.js';

// @ts-expect-error extensao de toJSON para serializacao JSON nativa de BigInt
if (!BigInt.prototype.toJSON) {
    // @ts-expect-error extensao de toJSON para serializacao JSON nativa de BigInt
    BigInt.prototype.toJSON = function () {
        return this.toString();
    };
}

const bridgeVersion = typeof __BRIDGE_VERSION__ !== 'undefined' ? __BRIDGE_VERSION__ : 'dev';
const baileysVersion = typeof __BAILEYS_VERSION__ !== 'undefined' ? __BAILEYS_VERSION__ : 'dev';

async function main(): Promise<void> {
    // pino already includes pid/hostname on every line by default.
    const logger = childLogger({ component: 'bridge' });
    const token = readExpectedToken();
    const host = process.env.ANGATU_BRIDGE_HOST ?? '127.0.0.1';
    const requestedPort = process.env.ANGATU_BRIDGE_PORT ? Number.parseInt(process.env.ANGATU_BRIDGE_PORT, 10) : 0;

    if (host !== '127.0.0.1' && host !== 'localhost' && host !== '::1') {
        logger.warn({ host }, 'bridge esta escutando fora de loopback - configuracao explicita, garanta que a rede e confiavel');
    }

    const server = new BridgeServer(host, requestedPort, token, logger, { bridgeVersion, baileysVersion });
    await server.waitUntilListening();

    // Sentinel log line the Java BridgeProcessManager watches for on stdout
    // to learn which port the OS actually assigned (avoids any pre-allocate
    // then hand off race between two separate processes).
    logger.info({ port: server.actualPort, bridgeVersion, baileysVersion }, 'bridge_ready');

    // Ficar sem cliente por tempo demais significa que a JVM que nos subiu
    // morreu sem executar hook nenhum. Continuar vivo manteria o socket do
    // WhatsApp aberto sem dono — e um segundo socket na mesma conta e o que faz
    // o servidor responder connectionReplaced em looping.
    server.onIdleShutdown = () => process.exit(0);

    let shuttingDown = false;
    const shutdown = async (signal: string) => {
        if (shuttingDown) return;
        shuttingDown = true;
        logger.info({ signal }, 'shutting down');
        try {
            await server.close();
            process.exit(0);
        } catch (err) {
            logger.error({ err }, 'error during shutdown');
            process.exit(1);
        }
    };

    process.on('SIGINT', () => void shutdown('SIGINT'));
    process.on('SIGTERM', () => void shutdown('SIGTERM'));

    // Ultima linha de defesa para a trava de sessao: 'exit' e sincrono e roda
    // em qualquer saida do processo, inclusive nas fatais abaixo. Deixar a
    // trava para tras nao trava a conta para sempre (o proximo processo a
    // reconhece como orfa), mas gera um aviso e um susto desnecessarios.
    process.on('exit', () => {
        try {
            server.instanceManager.releaseAllLocks();
        } catch {
            // saida do processo nao e lugar de lancar
        }
    });

    process.on('uncaughtException', (err) => {
        logger.fatal({ err }, 'uncaught exception - terminating process');
        process.exit(1);
    });
    process.on('unhandledRejection', (reason) => {
        logger.fatal({ err: reason }, 'unhandled rejection - terminating process');
        process.exit(1);
    });
}

main().catch((err) => {
    // Logger may not be available yet if construction itself failed
    // (e.g. missing token), so fall back to stderr for this one case.
    console.error('[bridge] fatal startup error:', err);
    process.exit(1);
});
