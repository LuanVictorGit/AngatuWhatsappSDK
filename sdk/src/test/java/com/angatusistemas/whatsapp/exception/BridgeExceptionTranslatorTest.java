package com.angatusistemas.whatsapp.exception;

import com.angatusistemas.whatsapp.protocol.BridgeErrorCode;
import com.angatusistemas.whatsapp.protocol.ErrorPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies every {@link BridgeErrorCode} maps to the specific
 * {@link WhatsAppException} subtype documented on
 * {@link BridgeExceptionTranslator}, and that an unrecognized wire code
 * degrades to {@link BridgeErrorCode#UNKNOWN} / {@link BridgeException}
 * rather than throwing during translation itself.
 */
class BridgeExceptionTranslatorTest {

    static Stream<Arguments> codeToExceptionType() {
        return Stream.of(
                Arguments.of(BridgeErrorCode.INSTANCE_NOT_FOUND, InstanceException.class),
                Arguments.of(BridgeErrorCode.INSTANCE_ALREADY_EXISTS, InstanceException.class),
                Arguments.of(BridgeErrorCode.NOT_CONNECTED, ConnectionException.class),
                Arguments.of(BridgeErrorCode.CONNECTION_FAILED, ConnectionException.class),
                Arguments.of(BridgeErrorCode.AUTH_FAILED, AuthenticationException.class),
                Arguments.of(BridgeErrorCode.SEND_FAILED, MessageSendException.class),
                Arguments.of(BridgeErrorCode.MEDIA_NOT_FOUND, MessageSendException.class),
                Arguments.of(BridgeErrorCode.TIMEOUT, com.angatusistemas.whatsapp.exception.TimeoutException.class),
                Arguments.of(BridgeErrorCode.UNSUPPORTED_FEATURE, UnsupportedFeatureException.class),
                Arguments.of(BridgeErrorCode.INVALID_TOKEN, BridgeException.class),
                Arguments.of(BridgeErrorCode.PROTOCOL_VERSION_MISMATCH, BridgeException.class),
                Arguments.of(BridgeErrorCode.INVALID_MESSAGE, BridgeException.class),
                Arguments.of(BridgeErrorCode.UNKNOWN_ACTION, BridgeException.class),
                Arguments.of(BridgeErrorCode.INVALID_PAYLOAD, BridgeException.class),
                Arguments.of(BridgeErrorCode.INTERNAL_ERROR, BridgeException.class),
                Arguments.of(BridgeErrorCode.UNKNOWN, BridgeException.class));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("codeToExceptionType")
    void translatesEveryBridgeErrorCodeToItsDocumentedExceptionType(
            BridgeErrorCode code, Class<? extends WhatsAppException> expectedType) {
        ErrorPayload error = new ErrorPayload(code.name(), "mensagem de teste", Map.of());

        WhatsAppException translated = BridgeExceptionTranslator.translate("inst-1", error);

        assertEquals(expectedType, translated.getClass());
    }

    @Test
    void unrecognizedWireCodeFallsBackToUnknownAndBridgeException() {
        ErrorPayload error = new ErrorPayload("SOME_FUTURE_CODE_THIS_SDK_DOES_NOT_KNOW_YET", "novidade", Map.of());

        WhatsAppException translated = BridgeExceptionTranslator.translate("inst-1", error);

        assertEquals(BridgeException.class, translated.getClass());
        assertEquals(BridgeErrorCode.UNKNOWN, translated.errorCode().orElseThrow());
    }

    @Test
    void translatedExceptionCarriesFullDiagnosticContext() {
        ErrorPayload error = new ErrorPayload(
                "INSTANCE_NOT_FOUND", "instancia desconhecida", Map.of("instanceId", "ghost"));

        WhatsAppException translated = BridgeExceptionTranslator.translate("ghost", error);

        assertEquals("ghost", translated.instanceId().orElseThrow());
        assertEquals(BridgeErrorCode.INSTANCE_NOT_FOUND, translated.errorCode().orElseThrow());
        assertEquals(Map.of("instanceId", "ghost"), translated.details());
        assertTrue(translated.getMessage().contains("instancia desconhecida"));
    }
}
