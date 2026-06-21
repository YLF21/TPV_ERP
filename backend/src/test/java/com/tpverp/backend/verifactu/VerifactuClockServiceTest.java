package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

class VerifactuClockServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-19T10:00:00Z");

    @Test
    void noAvisaCuandoLaHoraDePostgresqlDifiereCincoMinutosOMenos() {
        var service = service(NOW.plusSeconds(300));

        var status = service.check();

        assertThat(status.warning()).isFalse();
        assertThat(status.warningCode()).isNull();
        assertThat(status.driftSeconds()).isEqualTo(300);
    }

    @Test
    void avisaCuandoLaHoraDePostgresqlDifiereMasDeCincoMinutos() {
        var service = service(NOW.minusSeconds(301));

        var status = service.check();

        assertThat(status.warning()).isTrue();
        assertThat(status.warningCode()).isEqualTo("CLOCK_DRIFT_OVER_5_MINUTES");
        assertThat(status.driftSeconds()).isEqualTo(-301);
        assertThat(status.thresholdSeconds()).isEqualTo(300);
    }

    private static VerifactuClockService service(Instant databaseTime) {
        var jdbc = Mockito.mock(JdbcTemplate.class);
        when(jdbc.queryForObject("select current_timestamp", Timestamp.class))
                .thenReturn(Timestamp.from(databaseTime));
        return new VerifactuClockService(
                jdbc, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
