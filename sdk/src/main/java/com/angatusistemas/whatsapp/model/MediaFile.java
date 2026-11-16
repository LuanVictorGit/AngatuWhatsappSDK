package com.angatusistemas.whatsapp.model;

import java.nio.file.Path;

/**
 * Media downloaded straight to disk, never held whole in memory on either side of the bridge.
 *
 * @author Angatu Sistemas
 */
public record MediaFile(Path path, String mimetype, long size) {
}
