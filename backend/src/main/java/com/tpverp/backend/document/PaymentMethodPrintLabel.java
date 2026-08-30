package com.tpverp.backend.document;

import java.util.Locale;

/** Keeps persisted payment method identifiers separate from their printed label. */
public final class PaymentMethodPrintLabel {

    private PaymentMethodPrintLabel() {
    }

    public static String format(String method) {
        if (method == null || method.isBlank()) {
            return "PAGO";
        }
        return method.trim().toUpperCase(Locale.ROOT).replace('_', ' ');
    }
}
