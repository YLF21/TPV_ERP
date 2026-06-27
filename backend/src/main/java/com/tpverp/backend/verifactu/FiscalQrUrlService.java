package com.tpverp.backend.verifactu;

import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class FiscalQrUrlService {

    private static final String PRODUCTION_BASE =
            "https://www2.agenciatributaria.gob.es/wlpl/TIKE-CONT/ValidarQR";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-uuuu");

    // Generates the official URL encoded inside the tax QR.
    public String productionUrl(FiscalRecord record) {
        Objects.requireNonNull(record, "record");
        if (record.getTotalAmount() == null) {
            throw new IllegalArgumentException("importe total es obligatorio para el QR");
        }
        return PRODUCTION_BASE
                + "?nif=" + encode(record.getIssuerTaxId())
                + "&numserie=" + encode(record.getNumber())
                + "&fecha=" + encode(DATE.format(record.getIssueDate()))
                + "&importe=" + encode(record.getTotalAmount()
                        .setScale(2, RoundingMode.HALF_UP)
                        .toPlainString());
    }

    private static String encode(String value) {
        return URLEncoder.encode(Objects.requireNonNull(value, "value"), StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
