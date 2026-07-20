package com.tpverp.backend.terminal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Fiscal line quantities attached durably to a card refund operation. */
public record PaymentTerminalRefundLineSelection(UUID lineId, BigDecimal quantity) {
    public PaymentTerminalRefundLineSelection {
        Objects.requireNonNull(lineId, "lineId");
        Objects.requireNonNull(quantity, "quantity");
        if (quantity.signum() <= 0 || quantity.stripTrailingZeros().scale() > 3) {
            throw new IllegalArgumentException("La cantidad a devolver debe ser positiva y admitir maximo tres decimales");
        }
        quantity = quantity.setScale(3, RoundingMode.UNNECESSARY);
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
            parts.add(selection.lineId() + "=" + selection.quantity().stripTrailingZeros().toPlainString());
        }
        return String.join(";", parts);
    }

    public static List<PaymentTerminalRefundLineSelection> parse(String canonical) {
        if (canonical == null || canonical.isBlank()) return List.of();
        var result = new ArrayList<PaymentTerminalRefundLineSelection>();
        for (var part : canonical.split(";", -1)) {
            var fields = part.split("=", -1);
            if (fields.length != 2) throw new IllegalArgumentException("Desglose fiscal de devolucion invalido");
            result.add(new PaymentTerminalRefundLineSelection(UUID.fromString(fields[0]), new BigDecimal(fields[1])));
        }
        if (!canonical(result).equals(canonical)) {
            throw new IllegalArgumentException("El desglose fiscal de devolucion no es canonico");
        }
        return List.copyOf(result);
    }
}
