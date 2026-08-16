package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record VoucherView(
        String code,
        String familyIdentifier,
        BigDecimal initialAmount,
        BigDecimal balance,
        VoucherEffectiveStatus status,
        Instant createdAt,
        LocalDate expiresOn,
        List<String> originTickets) {

    public static VoucherView from(Voucher voucher, LocalDate today) {
        return new VoucherView(
                voucher.code(), voucher.familyIdentifier(),
                voucher.initialAmount(), voucher.balance(),
                voucher.effectiveStatus(today), voucher.createdAt(), voucher.expiresOn(),
                voucher.originTickets());
    }
}
