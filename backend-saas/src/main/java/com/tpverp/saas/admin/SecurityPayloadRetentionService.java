package com.tpverp.saas.admin;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SecurityPayloadRetentionService {

    static final String PURGED_PAYLOAD = "__PURGED__";

    private final JdbcTemplate jdbc;
    private final AdminAuditService audit;
    private final Clock clock;
    private final Duration retention;
    private final Duration integrationRetention;
    private final int batchSize;

    public SecurityPayloadRetentionService(
            JdbcTemplate jdbc,
            AdminAuditService audit,
            Clock clock,
            @Value("${tpv.saas.security-notifications.payload-retention:P30D}") Duration retention,
            @Value("${tpv.saas.integrations.payload-retention:P30D}") Duration integrationRetention,
            @Value("${tpv.saas.outbox.payload-purge-batch-size:500}") int batchSize) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        if (retention == null || retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("payload-retention debe ser positivo");
        }
        this.retention = retention;
        if (integrationRetention == null || integrationRetention.isZero() || integrationRetention.isNegative()) {
            throw new IllegalArgumentException("integrations.payload-retention debe ser positivo");
        }
        if (batchSize < 1 || batchSize > 5000) {
            throw new IllegalArgumentException("payload-purge-batch-size debe estar entre 1 y 5000");
        }
        this.integrationRetention = integrationRetention;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${tpv.saas.security-notifications.payload-purge-delay:86400000}")
    @Transactional
    public int purgeExpired() {
        int securityPurged = jdbc.update("""
                with candidates as (
                    select id from saas_security_notification_outbox
                     where status in ('DELIVERED', 'ACKNOWLEDGED')
                       and encrypted_payload <> ?
                       and coalesce(delivered_at, created_at) <= ?
                     order by coalesce(delivered_at, created_at), id
                     for update skip locked
                     limit ?
                )
                update saas_security_notification_outbox o
                   set encrypted_payload = ?
                  from candidates c where o.id = c.id
                """, PURGED_PAYLOAD, Timestamp.from(clock.instant().minus(retention)),
                batchSize, PURGED_PAYLOAD);
        if (securityPurged > 0) {
            audit.log("PURGE_SECURITY_OUTBOX_PAYLOADS", "SECURITY_NOTIFICATION_OUTBOX",
                    "BULK", "purged=" + securityPurged);
        }
        int integrationPurged = jdbc.update("""
                with candidates as (
                    select id from saas_integration_run
                     where status in ('SUCCEEDED', 'ACKNOWLEDGED')
                       and payload <> ?
                       and coalesce(completed_at, started_at) <= ?
                     order by coalesce(completed_at, started_at), id
                     for update skip locked
                     limit ?
                )
                update saas_integration_run r
                   set payload = ?
                  from candidates c where r.id = c.id
                """, PURGED_PAYLOAD, Timestamp.from(clock.instant().minus(integrationRetention)),
                batchSize, PURGED_PAYLOAD);
        if (integrationPurged > 0) {
            audit.log("PURGE_INTEGRATION_OUTBOX_PAYLOADS", "INTEGRATION_RUN",
                    "BULK", "purged=" + integrationPurged);
        }
        return securityPurged + integrationPurged;
    }
}
