package com.angatusistemas.whatsapp.options;

/**
 * Settings of the bridge process, which is one per JVM and shared by every client: the first
 * client to start it decides them. maxHeapMb caps the bridge heap so a runaway grows into a clean
 * restart instead of the container memory limit.
 *
 * @author Angatu Sistemas
 */
public record BridgeOptions(String nodeVersion, String host, Integer port, String logLevel, int maxHeapMb) {

    public BridgeOptions {
        if (maxHeapMb < 64) {
            throw new IllegalArgumentException("maxHeapMb must be at least 64");
        }
        if (port != null && (port < 0 || port > 65_535)) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
    }

    public static BridgeOptions defaults() {
        return new BridgeOptions(null, null, null, null, 512);
    }

    public BridgeOptions withNodeVersion(String version) {
        return new BridgeOptions(version, host, port, logLevel, maxHeapMb);
    }

    public BridgeOptions withEndpoint(String bindHost, Integer bindPort) {
        return new BridgeOptions(nodeVersion, bindHost, bindPort, logLevel, maxHeapMb);
    }

    public BridgeOptions withLogLevel(String level) {
        return new BridgeOptions(nodeVersion, host, port, level, maxHeapMb);
    }

    public BridgeOptions withMaxHeapMb(int megabytes) {
        return new BridgeOptions(nodeVersion, host, port, logLevel, megabytes);
    }
}
