package com.tpverp.backend.document;

import java.math.BigDecimal;

public record VoucherConsumptionView(
        BigDecimal consumedAmount,
        VoucherView consumedVoucher,
        VoucherView replacement) {

    public static VoucherConsumptionView from(VoucherConsumptionResult result) {
        return new VoucherConsumptionView(
                result.consumedAmount(),
                VoucherView.from(result.consumedVoucher()),
                result.replacement().map(VoucherView::from).orElse(null));
    }
}
