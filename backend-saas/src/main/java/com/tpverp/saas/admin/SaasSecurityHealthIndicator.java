package com.tpverp.saas.admin;

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

    @Autowired
    public SaasSecurityHealthIndicator(
            JdbcTemplate jdbc,
            IntegrationSecretCipher cipher,
            SecurityNotificationChannel securityChannel,
            IntegrationDeliveryChannel integrationChannel,
            @Value("${tpv.saas.security-notifications.required:true}") boolean securityChannelRequired,
            @Value("${tpv.saas.integrations.required:true}") boolean integrationChannelRequired,
            @Value("${tpv.saas.outbox.critical-backlog:1000}") long criticalBacklog) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.securityChannel = securityChannel;
        this.integrationChannel = integrationChannel;
        this.securityChannelRequired = securityChannelRequired;
        this.integrationChannelRequired = integrationChannelRequired;
        this.criticalBacklog = Math.max(1, criticalBacklog);
    }

    SaasSecurityHealthIndicator(JdbcTemplate jdbc, IntegrationSecretCipher cipher) {
        this(jdbc, cipher, notification -> true, delivery -> true, false, false, Long.MAX_VALUE);
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
            return Health.up().withDetail("securityStateStore", "available").build();
        } catch (RuntimeException exception) {
            return Health.down().withDetail("securityStateStore", "unavailable").build();
        }
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
