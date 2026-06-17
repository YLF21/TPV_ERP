package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record VoucherView(
        String code,
        BigDecimal initialAmount,
        BigDecimal balance,
        VoucherStatus status,
        Instant createdAt,
        List<String> originTickets) {

    public static VoucherView from(Voucher voucher) {
        return new VoucherView(
                voucher.code(), voucher.initialAmount(), voucher.balance(),
                voucher.status(), voucher.createdAt(), voucher.originTickets());
    }
}
