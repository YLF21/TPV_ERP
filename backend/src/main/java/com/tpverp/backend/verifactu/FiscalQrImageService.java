package com.tpverp.backend.verifactu;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class FiscalQrImageService {

    // Genera PNG del QR tributario con correccion M segun la especificacion AEAT.
    public FiscalQrImage png(String content, int size) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("contenido QR obligatorio");
        }
        if (size < 120 || size > 1024) {
            throw new IllegalArgumentException("tamano QR fuera de rango");
        }
        try {
            var output = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix(content, size), "PNG", output);
            return new FiscalQrImage(output.toByteArray(), "image/png");
        } catch (IOException | WriterException exception) {
            throw new IllegalStateException("No se pudo generar el QR fiscal", exception);
        }
    }

    private static BitMatrix matrix(String content, int size) throws WriterException {
        return new QRCodeWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                size,
                size,
                Map.of(
                        EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name(),
                        EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                        EncodeHintType.MARGIN, 4));
    }
}
