package com.tpverp.backend.verifactu;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Component;

@Component
public class FiscalPrintSnapshotFactory {

    public static final String FORMAT_VERSION = "AEAT_QR_0.5.0";
    public static final String PREFIX = "QR tributario:";
    public static final String VERIFACTU_LEGEND =
            "Factura verificable en la sede electrónica de la AEAT";
    public static final String TEST_NOTICE = "ENTORNO DE PRUEBAS - SIN VALIDEZ FISCAL";

    private final FiscalQrUrlService qrUrls;

    public FiscalPrintSnapshotFactory(FiscalQrUrlService qrUrls) {
        this.qrUrls = qrUrls;
    }

    public FiscalPrintSnapshot create(
            FiscalRecord record,
            FiscalMode mode,
            FiscalEndpointEnvironment environment,
            String generatorVersion) {
        var url = qrUrls.url(record, mode, environment);
        return new FiscalPrintSnapshot(
                FORMAT_VERSION,
                generatorVersion,
                mode,
                environment,
                url,
                sha256(url),
                PREFIX,
                mode == FiscalMode.VERIFACTU ? VERIFACTU_LEGEND : null,
                environment == FiscalEndpointEnvironment.TEST ? TEST_NOTICE : null);
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            var result = new StringBuilder(64);
            for (byte current : digest) {
                result.append(String.format("%02X", current));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }
}
