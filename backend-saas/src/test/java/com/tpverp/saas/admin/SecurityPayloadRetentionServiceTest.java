package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SecurityPayloadRetentionServiceTest {

    @Test
    void purgesOnlyTerminalPayloadsAndAuditsTheBatch() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AdminAuditService audit = mock(AdminAuditService.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(3);
        var service = new SecurityPayloadRetentionService(jdbc, audit,
                Clock.fixed(Instant.parse("2026-09-08T18:00:00Z"), ZoneOffset.UTC),
                Duration.ofDays(30), Duration.ofDays(30), 500);

        int purged = service.purgeExpired();

        assertThat(purged).isEqualTo(6);
        verify(jdbc).update(contains("status in ('DELIVERED', 'ACKNOWLEDGED')"), any(Object[].class));
        verify(jdbc).update(contains("status in ('SUCCEEDED', 'ACKNOWLEDGED')"), any(Object[].class));
        verify(audit).log("PURGE_SECURITY_OUTBOX_PAYLOADS", "SECURITY_NOTIFICATION_OUTBOX",
                "BULK", "purged=3");
        verify(audit).log("PURGE_INTEGRATION_OUTBOX_PAYLOADS", "INTEGRATION_RUN",
                "BULK", "purged=3");
    }
}
