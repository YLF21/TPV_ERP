package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.LocalDate;

public record VoucherConsumptionView(
        BigDecimal consumedAmount,
        VoucherView consumedVoucher,
        VoucherView replacement) {

    public static VoucherConsumptionView from(VoucherConsumptionResult result, LocalDate today) {
        return new VoucherConsumptionView(
                result.consumedAmount(),
                VoucherView.from(result.consumedVoucher(), today),
                result.replacement().map(value -> VoucherView.from(value, today)).orElse(null));
    }
}
