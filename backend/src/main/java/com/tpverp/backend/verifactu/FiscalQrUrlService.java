package com.tpverp.backend.verifactu;

import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class FiscalQrUrlService {

    private static final String PRODUCTION_VERIFACTU_BASE =
            "https://www2.agenciatributaria.gob.es/wlpl/TIKE-CONT/ValidarQR";
    private static final String PRODUCTION_NO_VERIFACTU_BASE =
            "https://www2.agenciatributaria.gob.es/wlpl/TIKE-CONT/ValidarQRNoVerifactu";
    private static final String TEST_VERIFACTU_BASE =
            "https://prewww2.aeat.es/wlpl/TIKE-CONT/ValidarQR";
    private static final String TEST_NO_VERIFACTU_BASE =
            "https://prewww2.aeat.es/wlpl/TIKE-CONT/ValidarQRNoVerifactu";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-uuuu");

    // Generates the official URL encoded inside the tax QR.
    public String productionUrl(FiscalRecord record) {
        return url(record, FiscalMode.VERIFACTU, FiscalEndpointEnvironment.PRODUCTION);
    }

    public String testUrl(FiscalRecord record) {
        return url(record, FiscalMode.VERIFACTU, FiscalEndpointEnvironment.TEST);
    }

    public String productionNoVerifactuUrl(FiscalRecord record) {
        return url(record, FiscalMode.NO_VERIFACTU, FiscalEndpointEnvironment.PRODUCTION);
    }

    public String testNoVerifactuUrl(FiscalRecord record) {
        return url(record, FiscalMode.NO_VERIFACTU, FiscalEndpointEnvironment.TEST);
    }

    /** Builds the exact four-parameter AEAT QR URL for a frozen fiscal mode. */
    public String url(
            FiscalRecord record,
            FiscalMode mode,
            FiscalEndpointEnvironment environment) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(environment, "environment");
        if (mode == FiscalMode.PRE_SIF) {
            throw new IllegalArgumentException("PRE_SIF no puede generar un QR fiscal");
        }
        if (record.getTotalAmount() == null) {
            throw new IllegalArgumentException("importe total es obligatorio para el QR");
        }
        return base(mode, environment)
                + "?nif=" + encode(record.getIssuerTaxId())
                + "&numserie=" + encode(record.getNumber())
                + "&fecha=" + encode(DATE.format(record.getIssueDate()))
                + "&importe=" + encode(record.getTotalAmount()
                        .setScale(2, RoundingMode.HALF_UP)
                        .toPlainString());
    }

    private static String base(FiscalMode mode, FiscalEndpointEnvironment environment) {
        if (environment == FiscalEndpointEnvironment.TEST) {
            return mode == FiscalMode.NO_VERIFACTU
                    ? TEST_NO_VERIFACTU_BASE : TEST_VERIFACTU_BASE;
        }
        return mode == FiscalMode.NO_VERIFACTU
                ? PRODUCTION_NO_VERIFACTU_BASE : PRODUCTION_VERIFACTU_BASE;
    }

    private static String encode(String value) {
        return URLEncoder.encode(Objects.requireNonNull(value, "value"), StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
