package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VoucherExpirationTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-08-16T10:00:00Z");
    private static final LocalDate EXPIRES_ON = LocalDate.of(2027, 8, 16);

    @Test
    void voucherRemainsActiveForTheWholeExpiryDate() {
        var voucher = voucher(EXPIRES_ON);

        assertThat(voucher.effectiveStatus(EXPIRES_ON))
                .isEqualTo(VoucherEffectiveStatus.ACTIVE);
        assertThat(voucher.effectiveStatus(EXPIRES_ON.plusDays(1)))
                .isEqualTo(VoucherEffectiveStatus.EXPIRED);
        assertThat(voucher.balance()).isEqualByComparingTo("50.00");
    }

    @Test
    void onlyAnExpiredVoucherCanBeReactivatedWithAFutureExpiration() {
        var voucher = voucher(EXPIRES_ON);
        var today = EXPIRES_ON.plusDays(1);
        var reactivatedUntil = today.plusDays(30);

        voucher.reactivate(reactivatedUntil, today);

        assertThat(voucher.expiresOn()).isEqualTo(reactivatedUntil);
        assertThat(voucher.effectiveStatus(today))
                .isEqualTo(VoucherEffectiveStatus.ACTIVE);
        assertThatThrownBy(() -> voucher.reactivate(
                reactivatedUntil.plusDays(1), today))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("voucher_only_expired_can_reactivate");
    }

    @Test
    void reactivationAllowsTodayButRejectsAPastDate() {
        var today = EXPIRES_ON.plusDays(1);
        var usableToday = voucher(EXPIRES_ON);

        usableToday.reactivate(today, today);
        assertThat(usableToday.effectiveStatus(today))
                .isEqualTo(VoucherEffectiveStatus.ACTIVE);

        var voucher = voucher(EXPIRES_ON);

        assertThatThrownBy(() -> voucher.reactivate(today.minusDays(1), today))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("voucher_reactivation_expiration_must_be_today_or_future");
    }

    private Voucher voucher(LocalDate expiration) {
        return new Voucher(
                UUID.randomUUID(), "VEXPIRY", new BigDecimal("50.00"),
                List.of("T-1"), ISSUED_AT, expiration);
    }
}
