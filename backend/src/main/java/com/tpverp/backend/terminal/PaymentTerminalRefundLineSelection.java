package com.tpverp.backend.terminal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Fiscal line quantities attached durably to a card refund operation. */
public record PaymentTerminalRefundLineSelection(
        UUID lineId,
        BigDecimal quantity,
        List<String> serialNumbers) {
    public PaymentTerminalRefundLineSelection(UUID lineId, BigDecimal quantity) {
        this(lineId, quantity, List.of());
    }

    public PaymentTerminalRefundLineSelection {
        Objects.requireNonNull(lineId, "lineId");
        Objects.requireNonNull(quantity, "quantity");
        if (quantity.signum() <= 0 || quantity.stripTrailingZeros().scale() > 3) {
            throw new IllegalArgumentException("La cantidad a devolver debe ser positiva y admitir maximo tres decimales");
        }
        quantity = quantity.setScale(3, RoundingMode.UNNECESSARY);
        serialNumbers = normalizeSerialNumbers(serialNumbers);
    }

    public static String canonical(List<PaymentTerminalRefundLineSelection> selections) {
        if (selections == null || selections.isEmpty()) return "";
        var normalized = new ArrayList<>(selections);
        normalized.sort(java.util.Comparator.comparing(value -> value.lineId().toString()));
        var unique = new HashSet<UUID>();
        var parts = new ArrayList<String>();
        for (var selection : normalized) {
            if (selection == null || !unique.add(selection.lineId())) {
                throw new IllegalArgumentException("Cada linea fiscal solo puede aparecer una vez");
            }
            var serials = selection.serialNumbers().stream()
                    .map(PaymentTerminalRefundLineSelection::encode)
                    .sorted()
                    .toList();
            parts.add(selection.lineId() + "=" + selection.quantity().stripTrailingZeros().toPlainString()
                    + (serials.isEmpty() ? "" : "#" + String.join(",", serials)));
        }
        return String.join(";", parts);
    }

    public static List<PaymentTerminalRefundLineSelection> parse(String canonical) {
        if (canonical == null || canonical.isBlank()) return List.of();
        var result = new ArrayList<PaymentTerminalRefundLineSelection>();
        for (var part : canonical.split(";", -1)) {
            var fields = part.split("=", 2);
            if (fields.length != 2) throw new IllegalArgumentException("Desglose fiscal de devolucion invalido");
            var quantityAndSerials = fields[1].split("#", 2);
            var serials = quantityAndSerials.length == 1 || quantityAndSerials[1].isBlank()
                    ? List.<String>of()
                    : java.util.Arrays.stream(quantityAndSerials[1].split(",", -1))
                            .map(PaymentTerminalRefundLineSelection::decode)
                            .toList();
            result.add(new PaymentTerminalRefundLineSelection(
                    UUID.fromString(fields[0]), new BigDecimal(quantityAndSerials[0]), serials));
        }
        if (!canonical(result).equals(canonical)) {
            throw new IllegalArgumentException("El desglose fiscal de devolucion no es canonico");
        }
        return List.copyOf(result);
    }

    private static List<String> normalizeSerialNumbers(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        var result = new ArrayList<String>();
        var unique = new HashSet<String>();
        for (var value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("El numero de serie de la devolucion es obligatorio");
            }
            var serial = value.trim();
            if (serial.length() > 128) {
                throw new IllegalArgumentException("El numero de serie no puede superar 128 caracteres");
            }
            if (!unique.add(serial.toUpperCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Un numero de serie no puede repetirse en la devolucion");
            }
            result.add(serial);
        }
        return List.copyOf(result);
    }

    private static String encode(String value) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        try {
            return new String(java.util.Base64.getUrlDecoder().decode(value),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Numero de serie de devolucion invalido", error);
        }
    }
}
