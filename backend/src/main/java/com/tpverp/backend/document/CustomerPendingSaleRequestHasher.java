package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class CustomerPendingSaleRequestHasher {

    private CustomerPendingSaleRequestHasher() {
    }

    static String hash(
            CustomerPendingSaleController.CreateRequest request,
            BigDecimal cardAmount,
            BigDecimal authoritativeTotal) {
        var canonical = new StringBuilder("v1")
                .append('|').append(request.checkoutId())
                .append('|').append(request.type())
                .append('|').append(request.customerId())
                .append('|').append(request.dueDate())
                .append('|').append(request.warehouseId())
                .append('|').append(request.date())
                .append('|').append(decimal(request.globalDiscount()))
                .append('|').append(Money.euros(cardAmount).toPlainString())
                .append('|').append(Money.euros(authoritativeTotal).toPlainString());
        var lines = request.lines() == null ? java.util.List.<DocumentRequest.LineRequest>of()
                : request.lines();
        for (var line : lines) {
            canonical.append('|').append(line.productoId())
                    .append(':').append(decimal(line.cantidad()))
                    .append(':').append(decimal(line.descuento()));
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String decimal(BigDecimal value) {
        if (value == null) {
            return "null";
        }
        var normalized = value.stripTrailingZeros();
        return normalized.signum() == 0 ? "0" : normalized.toPlainString();
    }
}
