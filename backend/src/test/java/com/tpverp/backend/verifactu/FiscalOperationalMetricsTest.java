package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.tpverp.backend.observability.TpvFiscalHealthIndicator;

class FiscalOperationalMetricsTest {

    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");

    @Test
    void publicaBacklogGlobalSinEtiquetasDeTenantYCalculaEdad() {
        var repository = mock(FiscalOperationalStatusRepository.class);
        when(repository.findGlobal()).thenReturn(new FiscalOperationalStatusSnapshot(
                Map.of(FiscalSubmissionStatus.PENDIENTE, 2L,
                        FiscalSubmissionStatus.ENVIANDO, 1L,
                        FiscalSubmissionStatus.ENVIADO, 4L),
                NOW.minusSeconds(125), NOW.minusSeconds(10), 3L));
        var meters = new SimpleMeterRegistry();
        var metrics = new FiscalOperationalMetrics(
                repository, meters, Clock.fixed(NOW, ZoneOffset.UTC));

        metrics.refresh();

        assertThat(meters.get("tpv.verifactu.backlog")
                .tag("status", "PENDIENTE").gauge().value()).isEqualTo(2d);
        assertThat(meters.get("tpv.verifactu.backlog").meters()).allMatch(meter ->
                meter.getId().getTags().stream().noneMatch(tag ->
                        tag.getKey().equals("company") || tag.getKey().equals("store")
                                || tag.getKey().equals("installation")));
        assertThat(meters.get("tpv.verifactu.oldest.pending.age.seconds")
                .gauge().value()).isEqualTo(125d);
        assertThat(meters.get("tpv.verifactu.leases.expired").gauge().value())
                .isEqualTo(3d);
        assertThat(meters.get("tpv.verifactu.last.aeat.success.epoch.seconds")
                .gauge().value()).isEqualTo(NOW.minusSeconds(10).getEpochSecond());
    }

    @Test
    void backlogNoPoneFiscalHealthEnDownPeroFalloDePersistenciaSi() {
        var repository = mock(FiscalOperationalStatusRepository.class);
        when(repository.findGlobal()).thenReturn(new FiscalOperationalStatusSnapshot(
                Map.of(FiscalSubmissionStatus.PENDIENTE, 9L,
                        FiscalSubmissionStatus.RECHAZADO, 2L),
                NOW.minusSeconds(3600), null, 0L));
        var metrics = new FiscalOperationalMetrics(
                repository, new SimpleMeterRegistry(), Clock.fixed(NOW, ZoneOffset.UTC));

        metrics.refresh();
        var health = new TpvFiscalHealthIndicator(metrics).health();

        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("pendingCount", 9L);
        assertThat(health.getDetails()).containsEntry("rejectedCount", 2L);
        verify(repository, times(1)).findGlobal();

        when(repository.findGlobal()).thenThrow(new IllegalStateException("db unavailable"));
        var failedCollection = org.assertj.core.api.Assertions.catchThrowable(metrics::refresh);
        assertThat(failedCollection).isInstanceOf(IllegalStateException.class);
        var failed = new TpvFiscalHealthIndicator(metrics).health();
        assertThat(failed.getStatus().getCode()).isEqualTo("DOWN");
        verify(repository, times(2)).findGlobal();
    }

    @Test
    void variasProbesNoVuelvenAConsultarYElSchedulerRecuperaLaCaché() {
        var repository = mock(FiscalOperationalStatusRepository.class);
        var clock = new MutableClock(NOW);
        when(repository.findGlobal())
                .thenReturn(new FiscalOperationalStatusSnapshot(
                        Map.of(FiscalSubmissionStatus.PENDIENTE, 1L), null, null, 0L))
                .thenThrow(new IllegalStateException("db unavailable"))
                .thenReturn(new FiscalOperationalStatusSnapshot(
                        Map.of(FiscalSubmissionStatus.PENDIENTE, 3L), null, null, 0L));
        var metrics = new FiscalOperationalMetrics(
                repository, new SimpleMeterRegistry(), clock, java.time.Duration.ofSeconds(30));
        var indicator = new TpvFiscalHealthIndicator(metrics);

        metrics.scheduledRefresh();
        assertThat(indicator.health().getStatus().getCode()).isEqualTo("UP");
        assertThat(indicator.health().getStatus().getCode()).isEqualTo("UP");
        verify(repository, times(1)).findGlobal();

        clock.advanceSeconds(31);
        assertThat(indicator.health().getStatus().getCode()).isEqualTo("DOWN");
        verify(repository, times(1)).findGlobal();

        metrics.scheduledRefresh();
        var failed = indicator.health();
        assertThat(failed.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(failed.getDetails()).containsEntry("refreshFailed", true);
        verify(repository, times(2)).findGlobal();

        metrics.scheduledRefresh();
        var recovered = indicator.health();
        assertThat(recovered.getStatus().getCode()).isEqualTo("UP");
        assertThat(recovered.getDetails()).containsEntry("refreshFailed", false);
        assertThat(recovered.getDetails()).containsEntry("pendingCount", 3L);
        verify(repository, times(3)).findGlobal();
    }

    @Test
    void readinessSinPrimeraRecogidaEsDownSinConsultarDuranteElProbe() {
        var repository = mock(FiscalOperationalStatusRepository.class);
        var metrics = new FiscalOperationalMetrics(
                repository, new SimpleMeterRegistry(), Clock.fixed(NOW, ZoneOffset.UTC));

        var health = new TpvFiscalHealthIndicator(metrics).health();

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsEntry("snapshotUsable", false);
        verify(repository, times(0)).findGlobal();
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advanceSeconds(long seconds) {
            current = current.plusSeconds(seconds);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
