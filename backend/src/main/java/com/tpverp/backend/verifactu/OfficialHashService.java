package com.tpverp.backend.verifactu;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

public final class OfficialHashService {

    private static final DateTimeFormatter GENERATED_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    // Calculates the official hash for a creation record in the order defined by AEAT.
    public String hash(AltaHashInput input) {
        return sha256(
                "IDEmisorFactura=" + text(input.issuerTaxId())
                + "&NumSerieFactura=" + text(input.invoiceNumber())
                + "&FechaExpedicionFactura=" + text(input.issueDate())
                + "&TipoFactura=" + text(input.invoiceType())
                + "&CuotaTotal=" + number(input.totalTax())
                + "&ImporteTotal=" + number(input.totalAmount())
                + "&Huella=" + text(input.previousHash())
                + "&FechaHoraHusoGenRegistro=" + GENERATED_AT.format(input.generatedAt()));
    }

    // Calculates the official hash for a cancellation record in the order defined by AEAT.
    public String hash(CancellationHashInput input) {
        return sha256(
                "IDEmisorFacturaAnulada=" + text(input.issuerTaxId())
                + "&NumSerieFacturaAnulada=" + text(input.cancelledInvoiceNumber())
                + "&FechaExpedicionFacturaAnulada=" + text(input.cancelledIssueDate())
                + "&Huella=" + text(input.previousHash())
                + "&FechaHoraHusoGenRegistro=" + GENERATED_AT.format(input.generatedAt()));
    }

    // Hash fields and order mandated for RegistroEvento (AEAT L12/L2E).
    public String hash(FiscalEventHashInput input) {
        return sha256(
                "NIF=" + text(input.systemTaxId())
                + "&ID=" + text(input.systemIdentifier())
                + "&IdSistemaInformatico=" + text(input.systemId())
                + "&Version=" + text(input.systemVersion())
                + "&NumeroInstalacion=" + text(input.installationNumber())
                + "&NIF=" + text(input.obligatedTaxId())
                + "&TipoEvento=" + text(input.eventType())
                + "&HuellaEvento=" + text(input.previousHash())
                + "&FechaHoraHusoGenEvento=" + GENERATED_AT.format(input.generatedAt()));
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
