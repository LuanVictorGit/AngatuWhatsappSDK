// Bundles the bridge into a single, dependency-free ECMAScript module.
//
// The output (dist/bridge.mjs) is copied by the "sdk" Maven module into
// src/main/resources/bridge/ and shipped inside the SDK jar. At runtime the
// Java SDK extracts it to a local cache directory and executes it with
// whichever Node.js binary it located (or downloaded) - no npm install and
// no node_modules directory are required on the end user's machine.
import { build } from 'esbuild';
import { writeFileSync, readFileSync, mkdirSync, copyFileSync } from 'node:fs';

const pkg = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8'));
const sdkResourcesDir = new URL('../sdk/src/main/resources/bridge/', import.meta.url);

mkdirSync(new URL('./dist/', import.meta.url), { recursive: true });
mkdirSync(sdkResourcesDir, { recursive: true });

const result = await build({
    entryPoints: ['src/index.ts'],
    outfile: 'dist/bridge.mjs',
    bundle: true,
    platform: 'node',
    target: 'node22',
    format: 'esm',
    minify: true,
    keepNames: true,
    // Kept for local dist/ debugging only - deliberately NOT copied into the
    // sdk resources below, since it would roughly double the embedded
    // artifact size for a use case (attaching a debugger to the bundled
    // bridge in production) that does not really exist.
    sourcemap: true,
    legalComments: 'none',
    metafile: true,
    // music-metadata pulls in strtok3/file-type which probe for native
    // addons that do not exist for this feature set; keeping bundling
    // strict (no external) surfaces that as a build error instead of a
    // runtime one, which is exactly what we want to catch here.
    logLevel: 'info',
    define: {
        __BRIDGE_VERSION__: JSON.stringify(pkg.version),
        __BAILEYS_VERSION__: JSON.stringify(pkg.dependencies.baileys)
    },
    banner: {
        js:
            '// Angatu WhatsApp SDK - Baileys Bridge (generated file, do not edit)\n' +
            `// baileys=${pkg.dependencies.baileys} builtAt=${new Date().toISOString()}\n` +
            "import { createRequire as __angatuCreateRequire } from 'node:module';\n" +
            'const require = __angatuCreateRequire(import.meta.url);\n'
    }
});

const buildInfo = {
    bridgeVersion: pkg.version,
    baileysVersion: pkg.dependencies.baileys,
    builtAt: new Date().toISOString(),
    nodeMinVersion: pkg.engines.node
};
writeFileSync(new URL('./dist/BUILD_INFO.json', import.meta.url), JSON.stringify(buildInfo, null, 2));

// The Java module consumes these two files directly as classpath resources
// (sdk/src/main/resources/bridge/) - no separate copy-resources step needed
// in the sdk pom, plain Maven resource processing picks them up.
copyFileSync(new URL('./dist/bridge.mjs', import.meta.url), new URL('./bridge.mjs', sdkResourcesDir));
writeFileSync(new URL('./BUILD_INFO.json', sdkResourcesDir), JSON.stringify(buildInfo, null, 2));

const bytes = Buffer.byteLength(readFileSync(new URL('./dist/bridge.mjs', import.meta.url)));
console.log(`[build] bridge.mjs written: ${(bytes / 1024 / 1024).toFixed(2)} MiB (dist/ and sdk resources/)`);

if (result.warnings.length > 0) {
    console.warn(`[build] ${result.warnings.length} warning(s) emitted during bundling`);
}
