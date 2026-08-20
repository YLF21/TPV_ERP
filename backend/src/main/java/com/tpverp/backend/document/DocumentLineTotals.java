package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.util.List;

public final class DocumentLineTotals {

    private DocumentLineTotals() {
    }

    public static BigDecimal memberBalanceTotal(List<DocumentLine> lines) {
        return total(lines, DocumentLine::getTotal);
    }

    public static BigDecimal memberBalanceBaseTotal(List<DocumentLine> lines) {
        return total(lines, DocumentLine::getBase);
    }

    public static boolean isMemberBalance(DocumentLine line) {
        return line.getLineType() == DocumentLineType.MEMBER_BALANCE;
    }

    private static BigDecimal total(
            List<DocumentLine> lines,
            java.util.function.Function<DocumentLine, BigDecimal> amount) {
        return Money.euros(lines.stream()
                .filter(DocumentLineTotals::isMemberBalance)
                .map(amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .negate());
    }
}
