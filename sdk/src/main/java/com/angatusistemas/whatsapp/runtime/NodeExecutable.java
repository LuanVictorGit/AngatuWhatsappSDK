package com.angatusistemas.whatsapp.runtime;

import java.nio.file.Path;

/**
 * The Node.js binary that runs the bridge, its version, and whether it came from the system or
 * was downloaded by the SDK.
 *
 * @author Angatu Sistemas
 */
public record NodeExecutable(Path path, NodeVersion version, Source source) {

    public enum Source {
        SYSTEM,
        DOWNLOADED
    }
}
