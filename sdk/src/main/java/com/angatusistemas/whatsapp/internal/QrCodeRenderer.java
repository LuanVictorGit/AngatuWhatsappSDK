package com.angatusistemas.whatsapp.internal;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Renders a QR code locally, as PNG bytes or as terminal text: the pairing secret never goes to an
 * external image service.
 *
 * @author Angatu Sistemas
 */
public final class QrCodeRenderer {

    private static final Map<EncodeHintType, Object> HINTS = Map.of(EncodeHintType.MARGIN, 1);

    private QrCodeRenderer() {
    }

    public static byte[] png(String content, int sizePixels) {
        BitMatrix matrix = encode(content, sizePixels);
        BufferedImage image = new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_BYTE_BINARY);
        for (int x = 0; x < matrix.getWidth(); x++) {
            for (int y = 0; y < matrix.getHeight(); y++) {
                image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(4_096);
        try {
            ImageIO.write(image, "PNG", out);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to encode the QR code as PNG", e);
        }
        return out.toByteArray();
    }

    // Two matrix rows per text line with half blocks, so the code stays square in a terminal.
    public static String text(String content) {
        BitMatrix matrix = encode(content, 0);
        StringBuilder out = new StringBuilder(matrix.getWidth() * matrix.getHeight() / 2 + matrix.getHeight());
        for (int y = 0; y < matrix.getHeight(); y += 2) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                boolean top = matrix.get(x, y);
                boolean bottom = y + 1 < matrix.getHeight() && matrix.get(x, y + 1);
                out.append(top && bottom ? ' ' : top ? '▄' : bottom ? '▀' : '█');
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static BitMatrix encode(String content, int sizePixels) {
        try {
            return new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePixels, sizePixels, HINTS);
        } catch (WriterException e) {
            throw new IllegalArgumentException("content cannot be encoded as a QR code", e);
        }
    }
}
