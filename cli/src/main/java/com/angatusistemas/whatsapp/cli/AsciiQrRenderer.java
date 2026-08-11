package com.angatusistemas.whatsapp.cli;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.PrintStream;
import java.util.Map;

/**
 * Renders a QR code as plain-ASCII block characters directly in the
 * terminal, so pairing a device from the CLI needs no external image
 * viewer. Independent of {@code QrCodeRenderer} in the sdk module's
 * {@code internal} package: that one renders a PNG for GUI applications,
 * this one is terminal-only and belongs to this module, not the public SDK
 * surface.
 *
 * <p>Deliberately uses {@code #}/space rather than Unicode block characters
 * (e.g. U+2588): on Windows, {@code System.out}'s encoding follows the
 * native console codepage (commonly {@code Cp1252}) even when stdout is
 * redirected rather than an interactive console, and {@code Cp1252} cannot
 * represent U+2588 - every module silently became a literal {@code ?}
 * during manual verification of this class. Plain ASCII has an unambiguous,
 * lossless encoding in every codepage and terminal, so this sidesteps both
 * that Java-side encoding step and any Windows console codepage the user's
 * terminal happens to be running.
 */
final class AsciiQrRenderer {

    private static final int MARGIN_MODULES = 2;
    private static final String DARK_MODULE = "##";
    private static final String LIGHT_MODULE = "  ";

    private AsciiQrRenderer() {
    }

    static void print(String content, PrintStream out) {
        try {
            Map<EncodeHintType, Object> hints = Map.of(
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L,
                    EncodeHintType.MARGIN, MARGIN_MODULES);
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints);

            StringBuilder sb = new StringBuilder();
            for (int y = 0; y < matrix.getHeight(); y++) {
                for (int x = 0; x < matrix.getWidth(); x++) {
                    sb.append(matrix.get(x, y) ? DARK_MODULE : LIGHT_MODULE);
                }
                sb.append(System.lineSeparator());
            }
            out.print(sb);
        } catch (WriterException e) {
            out.println("(nao foi possivel desenhar o QR Code no terminal: " + e.getMessage() + ")");
            out.println("conteudo bruto do QR Code: " + content);
        }
    }
}
