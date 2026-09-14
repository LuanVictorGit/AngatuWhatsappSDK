package com.angatusistemas.whatsapp.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Resposta de "este numero tem conta no WhatsApp?".
 *
 * @param jid o endereco que o <b>proprio WhatsApp</b> devolveu para o numero.
 *        Use este valor para enviar, e nao o numero que voce digitou: e o
 *        servidor quem sabe se a conta e a com ou a sem o nono digito
 * @param exists se ha conta de WhatsApp no numero
 * @param cached se a resposta veio do cache do bridge em vez de uma consulta
 *        nova (util para medir quantas consultas a aplicacao de fato gasta)
 */
public record NumberCheck(String jid, boolean exists, boolean cached) {

    public static NumberCheck fromJson(JsonNode node) {
        if (node == null) {
            return new NumberCheck(null, false, false);
        }
        return new NumberCheck(
                node.path("jid").asText(null),
                node.path("exists").asBoolean(false),
                node.path("cached").asBoolean(false));
    }
}
