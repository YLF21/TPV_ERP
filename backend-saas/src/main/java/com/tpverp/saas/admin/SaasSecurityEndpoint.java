package com.tpverp.saas.admin;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Endpoint(id = "saasSecurity")
public class SaasSecurityEndpoint {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public SaasSecurityEndpoint(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @ReadOperation
    public Map<String, Long> status() {
        return Map.of(
                "activeSessions", count("select count(*) from saas_session where expires_at > ?", true),
                "blockedLoginKeys", count("select count(*) from saas_login_attempt where blocked_until > ?", true),
                "pendingSecurityNotifications", count(
                        "select count(*) from saas_security_notification_outbox where status = 'PENDING'", false));
    }

    private long count(String sql, boolean timeParameter) {
        Long value = timeParameter
                ? jdbc.queryForObject(sql, Long.class, Timestamp.from(clock.instant()))
                : jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
