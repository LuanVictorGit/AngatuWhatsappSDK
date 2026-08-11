package com.angatusistemas.whatsapp.listener;

@FunctionalInterface
public interface QrCodeListener {
    /** {@code qrCode} is the raw QR payload string; render it with {@code WhatsAppClient.getQrCodePng()}. */
    void onQrCodeReceived(String qrCode);
}
