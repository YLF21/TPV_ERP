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
            BigDecimal authoritativeTotal) {
        var canonical = new StringBuilder("v1")
                .append('|').append(request.checkoutId())
                .append('|').append(request.type())
                .append('|').append(request.customerId())
                .append('|').append(request.dueDate())
                .append('|').append(request.warehouseId())
                .append('|').append(request.date())
                .append('|').append(decimal(request.globalDiscount()))
                .append('|').append(Money.euros(authoritativeTotal).toPlainString());
        var lines = request.lines() == null ? java.util.List.<DocumentRequest.LineRequest>of()
                : request.lines();
        for (var line : lines) {
            canonical.append('|').append(line.productoId())
                    .append(':').append(decimal(line.cantidad()))
                    .append(':').append(decimal(line.descuento()));
        }
        var payments = request.payments() == null
                ? java.util.List.<CustomerPendingSaleController.PaymentItem>of()
                : request.payments();
        for (int index = 0; index < payments.size(); index++) {
            var payment = payments.get(index);
            canonical.append("|payment:").append(index)
                    .append(':').append(payment.kind())
                    .append(':').append(payment.methodId())
                    .append(':').append(payment.requestId())
                    .append(':').append(decimal(payment.amount()))
                    .append(':').append(payment.principal())
                    .append(':').append(decimal(payment.delivered()))
                    .append(':').append(decimal(payment.change()))
                    .append(':').append(text(payment.voucherCode()))
                    .append(':').append(text(payment.reference()))
                    .append(':').append(payment.paymentTerminalOperationId());
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String text(String value) {
        return value == null ? "null" : value.trim();
    }

    private static String decimal(BigDecimal value) {
        if (value == null) {
            return "null";
        }
        var normalized = value.stripTrailingZeros();
        return normalized.signum() == 0 ? "0" : normalized.toPlainString();
    }
}
