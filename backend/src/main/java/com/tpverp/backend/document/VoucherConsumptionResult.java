package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.util.Optional;

public record VoucherConsumptionResult(
        Voucher consumedVoucher,
        BigDecimal consumedAmount,
        Optional<Voucher> replacement) {
}
