package com.angatusistemas.whatsapp.listener;

import com.angatusistemas.whatsapp.model.QrCode;

/**
 * Receives each QR code to show to whoever pairs the phone; it is a credential in flight, never log it.
 *
 * @author Angatu Sistemas
 */
@FunctionalInterface
public interface QrCodeListener {

    void onQrCodeReceived(QrCode qrCode);
}
