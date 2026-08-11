package com.angatusistemas.whatsapp.runtime;

import java.nio.file.Path;

/**
 * A Node.js binary this SDK has decided to use to run the bridge, either
 * found on the host system ({@link NodeRuntimeLocator}) or fetched on
 * demand into the local cache ({@link NodeRuntimeDownloader}).
 */
public record NodeExecutable(Path path, NodeVersion version, NodeSource source) {

    public enum NodeSource {
        SYSTEM,
        DOWNLOADED
    }
}
