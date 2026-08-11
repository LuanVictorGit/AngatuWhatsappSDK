package com.angatusistemas.whatsapp.internal;

import com.angatusistemas.whatsapp.exception.BridgeException;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Renders a WhatsApp pairing QR payload to a PNG image, using only
 * {@code zxing:core} (the actual encoder) plus {@code java.awt}/{@code javax.imageio}
 * (both JDK-builtin) for the bitmap-to-PNG conversion - avoids pulling in
 * the {@code zxing:javase} artifact for what is otherwise a dozen lines.
 */
public final class QrCodeRenderer {

    private static final int QUIET_ZONE_MODULES = 1;

    private QrCodeRenderer() {
    }

    public static byte[] renderPng(String qrContent, int sizePixels) {
        try {
            var hints = java.util.Map.of(com.google.zxing.EncodeHintType.MARGIN, QUIET_ZONE_MODULES);
            BitMatrix matrix = new QRCodeWriter().encode(qrContent, BarcodeFormat.QR_CODE, sizePixels, sizePixels, hints);
            BufferedImage image = toImage(matrix);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", out);
            return out.toByteArray();
        } catch (WriterException | IOException e) {
            throw new BridgeException("falha ao gerar imagem PNG do QR code", e);
        }
    }

    private static BufferedImage toImage(BitMatrix matrix) {
        int width = matrix.getWidth();
        int height = matrix.getHeight();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
            }
        }
        return image;
    }
}
