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
        var canonical = new Canonical().add("v2")
                .add(request.checkoutId()).add(request.type()).add(request.customerId())
                .add(request.dueDate()).add(request.warehouseId()).add(request.date())
                .add(decimal(request.globalDiscount()))
                .add(Money.euros(authoritativeTotal).toPlainString());
        var lines = request.lines() == null ? java.util.List.<DocumentRequest.LineRequest>of()
                : request.lines();
        canonical.add(lines.size());
        for (var line : lines) {
            canonical.add(line.productoId()).add(decimal(line.cantidad()))
                    .add(decimal(line.descuento()));
        }
        var payments = request.payments() == null
                ? java.util.List.<CustomerPendingSaleController.PaymentItem>of()
                : request.payments();
        canonical.add(payments.size());
        for (var payment : payments) {
            canonical.add(payment.kind()).add(payment.methodId()).add(payment.requestId())
                    .add(decimal(payment.amount())).add(payment.principal())
                    .add(decimal(payment.delivered())).add(decimal(payment.change()))
                    .add(text(payment.voucherCode())).add(text(payment.reference()))
                    .add(payment.paymentTerminalOperationId());
        }
        if (request.creditOverride() != null) {
            canonical.add("credit-override")
                    .add(text(request.creditOverride().reason()));
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.value().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String text(String value) {
        return value == null ? null : value.trim();
    }

    private static String decimal(BigDecimal value) {
        if (value == null) {
            return "null";
        }
        var normalized = value.stripTrailingZeros();
        return normalized.signum() == 0 ? "0" : normalized.toPlainString();
    }

    private static final class Canonical {
        private final StringBuilder value = new StringBuilder();

        Canonical add(Object field) {
            var encoded = field == null ? "" : field.toString();
            value.append(field == null ? -1 : encoded.length()).append('#').append(encoded);
            return this;
        }

        String value() {
            return value.toString();
        }
    }
}
