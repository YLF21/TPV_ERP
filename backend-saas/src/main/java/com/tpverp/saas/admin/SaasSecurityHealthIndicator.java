package com.tpverp.saas.admin;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component("saasSecurityHealthIndicator")
public class SaasSecurityHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbc;
    private final IntegrationSecretCipher cipher;
    private final SecurityNotificationChannel securityChannel;
    private final IntegrationDeliveryChannel integrationChannel;
    private final boolean securityChannelRequired;
    private final boolean integrationChannelRequired;
    private final long criticalBacklog;
    private final Duration maxPendingAge;
    private final Duration maxClaimAge;
    private final Clock clock;

    @Autowired
    public SaasSecurityHealthIndicator(
            JdbcTemplate jdbc,
            IntegrationSecretCipher cipher,
            SecurityNotificationChannel securityChannel,
            IntegrationDeliveryChannel integrationChannel,
            @Value("${tpv.saas.security-notifications.required:true}") boolean securityChannelRequired,
            @Value("${tpv.saas.integrations.required:true}") boolean integrationChannelRequired,
            @Value("${tpv.saas.outbox.critical-backlog:1000}") long criticalBacklog,
            @Value("${tpv.saas.outbox.max-pending-age:PT1H}") Duration maxPendingAge,
            @Value("${tpv.saas.outbox.max-claim-age:PT5M}") Duration maxClaimAge,
            Clock clock) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.securityChannel = securityChannel;
        this.integrationChannel = integrationChannel;
        this.securityChannelRequired = securityChannelRequired;
        this.integrationChannelRequired = integrationChannelRequired;
        this.criticalBacklog = Math.max(1, criticalBacklog);
        this.maxPendingAge = requirePositive(maxPendingAge, "max-pending-age");
        this.maxClaimAge = requirePositive(maxClaimAge, "max-claim-age");
        this.clock = clock;
    }

    SaasSecurityHealthIndicator(JdbcTemplate jdbc, IntegrationSecretCipher cipher) {
        this(jdbc, cipher, notification -> true, delivery -> true, false, false, Long.MAX_VALUE,
                Duration.ofDays(36500), Duration.ofDays(36500), Clock.systemUTC());
    }

    SaasSecurityHealthIndicator(
            JdbcTemplate jdbc,
            IntegrationSecretCipher cipher,
            SecurityNotificationChannel securityChannel,
            IntegrationDeliveryChannel integrationChannel,
            boolean securityChannelRequired,
            boolean integrationChannelRequired,
            long criticalBacklog) {
        this(jdbc, cipher, securityChannel, integrationChannel, securityChannelRequired,
                integrationChannelRequired, criticalBacklog, Duration.ofHours(1),
                Duration.ofMinutes(5), Clock.systemUTC());
    }

    @Override
    public Health health() {
        try {
            jdbc.queryForObject("select count(*) from saas_session", Long.class);
            if (!cipher.configured()) {
                return Health.down().withDetail("configuration", "security-notification-key-missing").build();
            }
            if ((securityChannelRequired && !securityChannel.available())
                    || (integrationChannelRequired && !integrationChannel.available())) {
                return Health.status("OUT_OF_SERVICE")
                        .withDetail("externalChannels", "required-channel-unavailable").build();
            }
            long pendingSecurity = count("""
                    select count(*) from saas_security_notification_outbox
                    where status in ('PENDING', 'PROCESSING')
                    """);
            long pendingIntegrations = count("""
                    select count(*) from saas_integration_run
                    where status in ('PENDING', 'PROCESSING')
                    """);
            if (pendingSecurity >= criticalBacklog || pendingIntegrations >= criticalBacklog) {
                return Health.status("OUT_OF_SERVICE")
                        .withDetail("outbox", "critical-backlog").build();
            }
            long failedSecurity = count("""
                    select count(*) from saas_security_notification_outbox where status = 'FAILED'
                    """);
            long failedIntegrations = count("""
                    select count(*) from saas_integration_run
                    where status = 'FAILED' and delivery_attempt_count > 0
                    """);
            if (failedSecurity > 0 || failedIntegrations > 0) {
                return Health.status("OUT_OF_SERVICE")
                        .withDetail("failedSecurityNotifications", failedSecurity)
                        .withDetail("failedIntegrationDeliveries", failedIntegrations)
                        .build();
            }
            Instant now = clock.instant();
            Timestamp expiredBefore = Timestamp.from(now.minus(maxClaimAge));
            long expiredSecurityClaims = count("""
                    select count(*) from saas_security_notification_outbox
                    where status = 'PROCESSING' and (claimed_at is null or claimed_at <= ?)
                    """, expiredBefore);
            long expiredIntegrationClaims = count("""
                    select count(*) from saas_integration_run
                    where status = 'PROCESSING' and (claimed_at is null or claimed_at <= ?)
                    """, expiredBefore);
            if (expiredSecurityClaims > 0 || expiredIntegrationClaims > 0) {
                return Health.status("OUT_OF_SERVICE")
                        .withDetail("expiredSecurityClaims", expiredSecurityClaims)
                        .withDetail("expiredIntegrationClaims", expiredIntegrationClaims)
                        .build();
            }
            Instant oldest = oldestPending();
            if (oldest != null && oldest.isBefore(now.minus(maxPendingAge))) {
                return Health.status("OUT_OF_SERVICE")
                        .withDetail("oldestPendingAt", oldest.toString()).build();
            }
            return Health.up().withDetail("securityStateStore", "available").build();
        } catch (RuntimeException exception) {
            return Health.down().withDetail("securityStateStore", "unavailable").build();
        }
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private Instant oldestPending() {
        Timestamp security = jdbc.queryForObject("""
                select min(created_at) from saas_security_notification_outbox
                where status in ('PENDING', 'PROCESSING')
                """, Timestamp.class);
        Timestamp integration = jdbc.queryForObject("""
                select min(started_at) from saas_integration_run
                where status in ('PENDING', 'PROCESSING')
                """, Timestamp.class);
        if (security == null) {
            return integration == null ? null : integration.toInstant();
        }
        if (integration == null) {
            return security.toInstant();
        }
        return security.before(integration) ? security.toInstant() : integration.toInstant();
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " debe ser positivo");
        }
        return value;
    }
}
