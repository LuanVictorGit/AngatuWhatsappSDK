import { childLogger } from './util/logger.js';
import { readExpectedToken } from './server/AuthToken.js';
import { BridgeServer } from './server/BridgeServer.js';

// Baileys hands BigInt values to JSON.stringify (receipts, timestamps).
const bigintPrototype = BigInt.prototype as unknown as { toJSON?: () => string };
if (!bigintPrototype.toJSON) {
    bigintPrototype.toJSON = function (this: bigint) {
        return this.toString();
    };
}

const bridgeVersion = typeof __BRIDGE_VERSION__ !== 'undefined' ? __BRIDGE_VERSION__ : 'dev';
const baileysVersion = typeof __BAILEYS_VERSION__ !== 'undefined' ? __BAILEYS_VERSION__ : 'dev';

/** More than this many uncaught exceptions in the window means the process state cannot be trusted. */
const MAX_FAULTS = 20;
const FAULT_WINDOW_MS = 60_000;

async function main(): Promise<void> {
    const logger = childLogger({ component: 'bridge' });
    const token = readExpectedToken();
    const host = process.env.ANGATU_BRIDGE_HOST ?? '127.0.0.1';
    const port = process.env.ANGATU_BRIDGE_PORT ? Number.parseInt(process.env.ANGATU_BRIDGE_PORT, 10) : 0;
    if (host !== '127.0.0.1' && host !== 'localhost' && host !== '::1') {
        logger.warn({ host }, 'bridge listening outside loopback - make sure that network is trusted');
    }

    const server = new BridgeServer(host, port, token, logger, { bridgeVersion, baileysVersion });
    await server.waitUntilListening();
    // Sentinel line the Java side watches for to learn the port the OS assigned.
    logger.info({ port: server.port, bridgeVersion, baileysVersion }, 'bridge_ready');

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
    process.on('exit', () => {
        try {
            server.instances.releaseAllLocks();
        } catch {
            // exiting: nowhere left to report
        }
    });

    // Baileys leaves stray rejections behind (a query that loses its socket mid-flight).
    // Dying on one took every account in the process down and forced all of them to
    // reconnect - the single largest source of "random" disconnections.
    process.on('unhandledRejection', (reason) => {
        logger.error({ err: reason }, 'unhandled rejection (process kept alive)');
    });
    const faults: number[] = [];
    process.on('uncaughtException', (err) => {
        const now = Date.now();
        faults.push(now);
        while (faults.length > 0 && now - faults[0]! > FAULT_WINDOW_MS) faults.shift();
        if (faults.length > MAX_FAULTS) {
            logger.fatal({ err }, 'too many uncaught exceptions - terminating so the SDK restarts a clean bridge');
            process.exit(1);
        }
        logger.error({ err }, 'uncaught exception (process kept alive)');
    });
}

main().catch((err) => {
    console.error('[bridge] fatal startup error:', err);
    process.exit(1);
});
