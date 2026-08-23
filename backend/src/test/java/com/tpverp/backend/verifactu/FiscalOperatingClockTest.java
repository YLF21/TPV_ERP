package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FiscalOperatingClockTest {
    private static final Instant START = Instant.parse("2026-08-23T10:00:00Z");
    private static final Duration MAX_GAP = Duration.ofMinutes(2);

    @Test
    void acumulaLatidosContinuosYMarcaLasSeisHoras() {
        var clock = new FiscalOperatingClock(UUID.randomUUID(), UUID.randomUUID(), START);

        for (var seconds = 60L; seconds <= Duration.ofHours(6).toSeconds(); seconds += 60) {
            clock.observe(START.plusSeconds(seconds), MAX_GAP);
        }

        assertThat(clock.getSecondsSinceSummary()).isEqualTo(Duration.ofHours(6).toSeconds());
        assertThat(clock.isDue(Duration.ofHours(6))).isTrue();
    }

    @Test
    void noCuentaElTiempoConElBackendDetenido() {
        var clock = new FiscalOperatingClock(UUID.randomUUID(), UUID.randomUUID(), START);

        clock.observe(START.plusSeconds(60), MAX_GAP);
        clock.observe(START.plusSeconds(Duration.ofHours(12).toSeconds()), MAX_GAP);
        clock.observe(START.plusSeconds(Duration.ofHours(12).toSeconds() + 60), MAX_GAP);

        assertThat(clock.getSecondsSinceSummary()).isEqualTo(120);
    }

    @Test
    void reinicioConservaElInstanteYEliminaElAcumulado() {
        var clock = new FiscalOperatingClock(UUID.randomUUID(), UUID.randomUUID(), START);
        clock.observe(START.plusSeconds(60), MAX_GAP);

        var resetAt = START.plusSeconds(120);
        clock.reset(resetAt);

        assertThat(clock.getObservedAt()).isEqualTo(resetAt);
        assertThat(clock.getSecondsSinceSummary()).isZero();
    }

    @Test
    void noPermiteRetrocederNiUsarIntervalosInvalidos() {
        var clock = new FiscalOperatingClock(UUID.randomUUID(), UUID.randomUUID(), START);

        assertThatThrownBy(() -> clock.observe(START.minusSeconds(1), MAX_GAP))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> clock.observe(START, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
