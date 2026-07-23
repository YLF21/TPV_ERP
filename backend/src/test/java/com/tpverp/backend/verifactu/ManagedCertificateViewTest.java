package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

class ManagedCertificateViewTest {

    private static final Instant NOW = Instant.parse("2026-07-23T00:00:00Z");

    @Test
    void roundsAnActivePartialDayUp() {
        var validUntil = NOW.plus(500, ChronoUnit.DAYS).plus(8, ChronoUnit.HOURS);

        assertThat(ManagedCertificateView.remainingDays(validUntil, NOW)).isEqualTo(501);
    }

    @Test
    void preservesAnExactNumberOfRemainingDays() {
        var validUntil = NOW.plus(500, ChronoUnit.DAYS);

        assertThat(ManagedCertificateView.remainingDays(validUntil, NOW)).isEqualTo(500);
    }

    @Test
    void reportsAPartialExpiredDayAsOneDayAgo() {
        var validUntil = NOW.minus(1, ChronoUnit.HOURS);

        assertThat(ManagedCertificateView.remainingDays(validUntil, NOW)).isEqualTo(-1);
    }

    @Test
    void reportsZeroAtTheExactExpirationInstant() {
        assertThat(ManagedCertificateView.remainingDays(NOW, NOW)).isZero();
    }
}
