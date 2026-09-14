package com.angatusistemas.whatsapp.pacing;

/**
 * Quando a SDK marca mensagens recebidas como lidas — o "azulzinho" no celular
 * de quem escreveu.
 *
 * <p><b>Contexto.</b> Ate esta versao a SDK <b>nunca</b> chamava
 * {@code readMessages}: uma conta podia receber milhares de mensagens e nao
 * marcar nenhuma como lida, o que nao e como nenhum cliente de WhatsApp de
 * verdade se comporta.
 *
 * <p>Ressalva que nao depende da SDK: se a conta estiver com "confirmacoes de
 * leitura" desligada nas configuracoes de privacidade do celular, a marcacao e
 * aceita mas o remetente nao ve o azul.
 */
public enum ReadReceiptMode {

    /**
     * Nunca marca nada como lido.
     *
     * <p>Para quem quer deliberadamente nao sinalizar leitura — um numero que so
     * dispara e nao atende, por exemplo.
     */
    OFF,

    /**
     * Nada automatico: so marca quando a aplicacao chama
     * {@code markRead}/{@code markChatRead}.
     *
     * <p>E o padrao porque nao muda o comportamento de quem ja usava a SDK antes
     * desta versao.
     */
    MANUAL,

    /**
     * Marca a conversa como lida logo <b>antes</b> de enviar uma resposta para
     * ela.
     *
     * <p>E a sequencia humana — leu, digitou, respondeu — e a opcao recomendada
     * para qualquer aplicacao que de fato responde as mensagens que recebe.
     * Nao marca nada em conversa para a qual nunca se responde, o que tambem
     * evita sinalizar leitura de uma campanha inteira.
     */
    ON_REPLY,

    /**
     * Marca toda mensagem recebida como lida, apos um atraso curto e aleatorio
     * ({@code readDelay}).
     *
     * <p>O atraso nao e enfeite: marcar como lida no mesmo milissegundo em que a
     * mensagem chega e, por si so, um padrao de automacao.
     */
    AUTO
}
