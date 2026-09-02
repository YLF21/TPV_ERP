package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    private JdbcTemplate healthyJdbc() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
        return jdbc;
    }
}
