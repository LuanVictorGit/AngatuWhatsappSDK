package com.angatusistemas.whatsapp;

import java.util.Arrays;

/**
 * Why a connection ended. The first six mirror {@code DisconnectCause} in
 * {@code bridge/src/adapter/BaileysAdapter.ts} - a bridge-reported cause
 * about the WhatsApp/Baileys connection itself. {@link #BRIDGE_CRASH} is
 * Java-side only: it means the bridge OS process died, which is not
 * something the bridge could ever report about itself.
 */
public enum DisconnectCause {
    /** The user (or another device) logged the session out; requires a new QR/pairing code. */
    USER_LOGOUT,
    /** Transient network/socket failure; the bridge already retries this automatically. */
    CONNECTION_LOST,
    /** WhatsApp asked the client to restart the connection; the bridge retries immediately. */
    WHATSAPP_RESTART,
    /** Credentials rejected (bad/expired session, banned number); requires a new QR/pairing code. */
    AUTH_FAILURE,
    /**
     * Outro cliente WhatsApp Web assumiu esta conta (o {@code connectionReplaced},
     * 440, do WhatsApp).
     *
     * <p><b>Isto nao e logout, e a credencial em disco continua valida.</b> A
     * distincao existe porque ate esta versao o 440 chegava como
     * {@link #USER_LOGOUT}, e toda aplicacao que reage a "deslogou" apagando a
     * sessao transformava um conflito recuperavel em repareamento forcado — que
     * numa conta ja sob observacao e o pior movimento possivel, porque
     * reapresenta um cliente web novo ao WhatsApp.
     *
     * <p>A resposta certa e <b>esperar</b>, e o bridge ja faz isso sozinho: ele
     * tenta voltar sem pressa (cinco minutos), o que evita a guerra de
     * reconexao com quem assumiu e ainda assim recupera a conta quando o outro
     * lado era um processo orfao. Nao apague credencial nenhuma ao receber esta
     * causa.
     */
    CONNECTION_REPLACED,
    /** The bridge OS process terminated unexpectedly. */
    BRIDGE_CRASH,
    /** Cause not recognized by this SDK version, or not applicable. */
    UNKNOWN;

    public static DisconnectCause fromWireValue(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        return Arrays.stream(values())
                .filter(cause -> cause.name().equals(value))
                .findFirst()
                .orElse(UNKNOWN);
    }
}
