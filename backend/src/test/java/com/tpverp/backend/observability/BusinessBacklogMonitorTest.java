package com.tpverp.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class BusinessBacklogMonitorTest {

    @Test
    void publishesOperationalBacklogsWithLowCardinalityKinds() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(2L, 3L, 4L);
        var meters = new SimpleMeterRegistry();
        var now = Instant.parse("2026-07-21T12:30:00Z");
        var monitor = new BusinessBacklogMonitor(
                jdbc, meters, Clock.fixed(now, ZoneOffset.UTC));

        var snapshot = monitor.refresh();

        assertThat(snapshot).isEqualTo(new BusinessBacklogMonitor.Snapshot(2L, 3L, 4L, now));
        assertThat(meters.get("tpv.business.backlog").tag("kind", "terminal_unresolved")
                .gauge().value()).isEqualTo(2.0d);
        assertThat(meters.get("tpv.business.backlog").tag("kind", "receivables_overdue")
                .gauge().value()).isEqualTo(3.0d);
        assertThat(meters.get("tpv.business.backlog").tag("kind", "promotions_active")
                .gauge().value()).isEqualTo(4.0d);
        assertThat(meters.get("tpv.business.monitor.last_success").gauge().value())
                .isEqualTo(now.getEpochSecond());
    }

    @Test
    void healthIsDownAndFailureMetricIncrementsWhenDatabaseSamplingFails() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class)))
                .thenThrow(new IllegalStateException("database unavailable"));
        var meters = new SimpleMeterRegistry();
        var monitor = new BusinessBacklogMonitor(jdbc, meters, Clock.systemUTC());

        var health = new TpvBusinessHealthIndicator(monitor).health();

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(meters.get("tpv.business.monitor.collection.failures").counter().count())
                .isEqualTo(1.0d);
    }
}
