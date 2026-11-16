package com.angatusistemas.whatsapp.options;

/**
 * When received messages get the blue ticks: never, only when the application asks, right before
 * this account replies in that chat (the way a person reads then answers), or automatically after
 * a short human delay.
 *
 * @author Angatu Sistemas
 */
public enum ReadReceiptMode {
    OFF,
    MANUAL,
    ON_REPLY,
    AUTO
}
