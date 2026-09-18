package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.util.Objects;

/** Formats the persisted member percentage consistently in printed documents. */
public final class MemberDiscountPrintLabel {

    private MemberDiscountPrintLabel() {
    }

    public static String format(BigDecimal percentage) {
        var normalized = Money.validPercentage(
                Objects.requireNonNull(percentage, "percentage"))
                .stripTrailingZeros()
                .toPlainString();
        return "Descuento miembro " + normalized + "%";
    }
}
