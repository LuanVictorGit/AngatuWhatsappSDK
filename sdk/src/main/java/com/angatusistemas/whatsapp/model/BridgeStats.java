package com.angatusistemas.whatsapp.model;

/**
 * Memory and uptime of the shared bridge process, to watch for growth over long runs; detailJson
 * carries the cache sizes of each account.
 *
 * @author Angatu Sistemas
 */
public record BridgeStats(
        long pid,
        long uptimeSeconds,
        long rssBytes,
        long heapUsedBytes,
        long heapTotalBytes,
        long externalBytes,
        int instances,
        String detailJson) {
}
