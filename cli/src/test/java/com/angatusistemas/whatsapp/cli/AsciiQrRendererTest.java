package com.angatusistemas.whatsapp.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsciiQrRendererTest {

    /** Roughly the shape of a real Baileys QR payload (ref,noiseKeyB64,identityKeyB64,advB64). */
    private static final String SAMPLE_QR_CONTENT =
            "2@AbCdEfGhIjKlMnOpQrStUvWxYz0123456789+/==,XyZaBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789+/==,1";

    @Test
    void rendersARectangularGridOfBlockCharacters() {
        String output = render(SAMPLE_QR_CONTENT);
        List<String> lines = output.lines().toList();

        assertFalse(lines.isEmpty());
        int expectedWidth = lines.get(0).length();
        assertTrue(expectedWidth > 0 && expectedWidth % 2 == 0, "cada modulo deveria virar 2 caracteres");
        for (String line : lines) {
            assertEquals(expectedWidth, line.length(), "todas as linhas deveriam ter a mesma largura");
        }
    }

    @Test
    void containsBothBlackAndWhiteModules() {
        String output = render(SAMPLE_QR_CONTENT);

        assertTrue(output.contains("##"), "deveria conter modulos escuros (padroes de localizacao sempre existem)");
        assertTrue(output.contains("  "), "deveria conter modulos claros (a margem de silencio sempre existe)");
    }

    @Test
    void outputIsPureAsciiRegardlessOfPlatformStdoutEncoding() {
        String output = render(SAMPLE_QR_CONTENT);

        for (int i = 0; i < output.length(); i++) {
            char c = output.charAt(i);
            assertTrue(c < 128, "caractere fora do intervalo ASCII encontrado: U+" + Integer.toHexString(c)
                    + " (blocos Unicode como U+2588 viram '?' quando stdout.encoding nao e UTF-8, ex.: Cp1252 no Windows)");
        }
    }

    @Test
    void differentContentProducesDifferentOutput() {
        String first = render(SAMPLE_QR_CONTENT);
        String second = render(SAMPLE_QR_CONTENT + "-different-suffix-to-change-the-payload");

        assertNotEquals(first, second);
    }

    private static String render(String content) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        AsciiQrRenderer.print(content, new PrintStream(buffer, true, StandardCharsets.UTF_8));
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
