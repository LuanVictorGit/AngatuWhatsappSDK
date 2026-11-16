package com.angatusistemas.whatsapp.model;

/**
 * A QR code to pair the account and the login cycle that produced it; a cycle lasts about two
 * minutes, and the client stops after the configured number of cycles without a scan.
 *
 * @author Angatu Sistemas
 */
public record QrCode(String value, int cycle) {
}
