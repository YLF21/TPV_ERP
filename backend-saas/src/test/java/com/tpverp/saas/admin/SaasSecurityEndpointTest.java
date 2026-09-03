package com.tpverp.saas.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SaasSecurityEndpointTest {

    @Test
    void exposesOnlyAggregateSecurityState() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Clock clock = Clock.fixed(Instant.parse("2026-09-02T10:00:00Z"), ZoneOffset.UTC);
        when(jdbc.queryForObject(
                "select count(*) from saas_session where expires_at > ?", Long.class,
                Timestamp.from(clock.instant())))
                .thenReturn(4L);
        when(jdbc.queryForObject(
                "select count(*) from saas_login_attempt where blocked_until > ?", Long.class,
                Timestamp.from(clock.instant())))
                .thenReturn(2L);
        when(jdbc.queryForObject(
                "select count(*) from saas_security_notification_outbox where status = 'PENDING'", Long.class))
                .thenReturn(1L);

        var status = new SaasSecurityEndpoint(jdbc, clock).status();

        assertEquals(4L, status.get("activeSessions"));
        assertEquals(2L, status.get("blockedLoginKeys"));
        assertEquals(1L, status.get("pendingSecurityNotifications"));
        assertEquals(3, status.size());
    }
}
