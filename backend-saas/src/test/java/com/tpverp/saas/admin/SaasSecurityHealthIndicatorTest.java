package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SaasSecurityHealthIndicatorTest {

    private static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Test
    void readinessRequiresDatabaseAndEncryptionKey() {
        JdbcTemplate jdbc = healthyJdbc();

        assertThat(new SaasSecurityHealthIndicator(jdbc, new IntegrationSecretCipher(KEY))
                .health().getStatus().getCode()).isEqualTo("UP");
        assertThat(new SaasSecurityHealthIndicator(jdbc, new IntegrationSecretCipher(""))
                .health().getStatus().getCode()).isEqualTo("DOWN");
    }

    @Test
    void requiredFallbackChannelMakesReadinessOutOfService() {
        JdbcTemplate jdbc = healthyJdbc();
        SecurityNotificationChannel fallback = new SecurityNotificationChannel() {
            @Override public boolean available() { return false; }
            @Override public boolean deliver(SecurityNotification notification) { return false; }
        };
        IntegrationDeliveryChannel integration = delivery -> true;

        var health = new SaasSecurityHealthIndicator(
                jdbc, new IntegrationSecretCipher(KEY), fallback, integration,
                true, true, 1000).health();

        assertThat(health.getStatus().getCode()).isEqualTo("OUT_OF_SERVICE");
    }

    @Test
    void failedDeliveryMakesReadinessOutOfService() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class)))
                .thenReturn(0L, 0L, 0L, 1L, 0L);

        var health = new SaasSecurityHealthIndicator(
                jdbc, new IntegrationSecretCipher(KEY), notification -> true, delivery -> true,
                false, false, 1000).health();

        assertThat(health.getStatus().getCode()).isEqualTo("OUT_OF_SERVICE");
        assertThat(health.getDetails()).containsEntry("failedSecurityNotifications", 1L);
    }

    @Test
    void oldPendingDeliveryMakesReadinessOutOfService() {
        JdbcTemplate jdbc = healthyJdbc();
        Instant now = Instant.parse("2026-09-08T12:00:00Z");
        when(jdbc.queryForObject(anyString(), eq(Timestamp.class)))
                .thenReturn(Timestamp.from(now.minus(Duration.ofHours(2))), (Timestamp) null);

        var health = new SaasSecurityHealthIndicator(
                jdbc, new IntegrationSecretCipher(KEY), notification -> true, delivery -> true,
                false, false, 1000, Duration.ofHours(1), Duration.ofMinutes(5),
                Clock.fixed(now, ZoneOffset.UTC)).health();

        assertThat(health.getStatus().getCode()).isEqualTo("OUT_OF_SERVICE");
        assertThat(health.getDetails()).containsKey("oldestPendingAt");
    }

    private JdbcTemplate healthyJdbc() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
        return jdbc;
    }
}
