package com.tpverp.backend.verifactu;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class OfficialHashService {

    // Calcula la huella oficial de un registro de alta en el orden definido por AEAT.
    public String hash(AltaHashInput input) {
        return sha256(
                "IDEmisorFactura=" + text(input.issuerTaxId())
                + "&NumSerieFactura=" + text(input.invoiceNumber())
                + "&FechaExpedicionFactura=" + text(input.issueDate())
                + "&TipoFactura=" + text(input.invoiceType())
                + "&CuotaTotal=" + number(input.totalTax())
                + "&ImporteTotal=" + number(input.totalAmount())
                + "&Huella=" + text(input.previousHash())
                + "&FechaHoraHusoGenRegistro=" + input.generatedAt());
    }

    // Calcula la huella oficial de un registro de anulacion en el orden definido por AEAT.
    public String hash(CancellationHashInput input) {
        return sha256(
                "IDEmisorFacturaAnulada=" + text(input.issuerTaxId())
                + "&NumSerieFacturaAnulada=" + text(input.cancelledInvoiceNumber())
                + "&FechaExpedicionFacturaAnulada=" + text(input.cancelledIssueDate())
                + "&Huella=" + text(input.previousHash())
                + "&FechaHoraHusoGenRegistro=" + input.generatedAt());
    }

    private static String number(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }
}
