package com.angatusistemas.whatsapp.model;

/**
 * The WhatsApp account an instance is logged in as.
 *
 * @author Angatu Sistemas
 */
public record Account(String jid, String phoneNumber, String name) {
}
