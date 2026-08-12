package com.tpverp.backend.document;

import java.math.BigDecimal;

public record DailyPaymentBreakdownView(
        BigDecimal cash,
        BigDecimal card,
        BigDecimal transfer,
        BigDecimal voucher,
        BigDecimal pending,
        BigDecimal other) {

    public DailyPaymentBreakdownView {
        cash = Money.euros(cash);
        card = Money.euros(card);
        transfer = Money.euros(transfer);
        voucher = Money.euros(voucher);
        pending = Money.euros(pending);
        other = Money.euros(other);
    }

    public static DailyPaymentBreakdownView zero() {
        var zero = Money.euros("0");
        return new DailyPaymentBreakdownView(zero, zero, zero, zero, zero, zero);
    }

    public DailyPaymentBreakdownView add(DailyPaymentBreakdownView otherBreakdown) {
        return new DailyPaymentBreakdownView(
                cash.add(otherBreakdown.cash),
                card.add(otherBreakdown.card),
                transfer.add(otherBreakdown.transfer),
                voucher.add(otherBreakdown.voucher),
                pending.add(otherBreakdown.pending),
                other.add(otherBreakdown.other));
    }

    public BigDecimal total() {
        return Money.euros(cash.add(card).add(transfer).add(voucher).add(pending).add(other));
    }
}
