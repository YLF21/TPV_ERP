package com.tpverp.saas.admin;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
public class SecurityNotificationDispatcher {

    private final JdbcTemplate jdbc;
    private final IntegrationSecretCipher cipher;
    private final SecurityNotificationChannel channel;
    private final Clock clock;
    private final Duration retryDelay;
    private final Duration claimLease;
    private final int maxAttempts;

    public SecurityNotificationDispatcher(
            JdbcTemplate jdbc,
            IntegrationSecretCipher cipher,
            SecurityNotificationChannel channel,
            Clock clock,
            @Value("${tpv.saas.security-notifications.retry-delay:PT15M}") Duration retryDelay,
            @Value("${tpv.saas.security-notifications.claim-lease:PT2M}") Duration claimLease,
            @Value("${tpv.saas.security-notifications.max-attempts:8}") int maxAttempts) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.channel = channel;
        this.clock = clock;
        this.retryDelay = requirePositive(retryDelay, "retry-delay");
        this.claimLease = requirePositive(claimLease, "claim-lease");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("max-attempts debe ser positivo");
        }
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${tpv.saas.security-notifications.poll-delay:60000}")
    public int dispatchPending() {
        Instant now = clock.instant();
        UUID claimToken = UUID.randomUUID();
        List<Pending> pending = jdbc.query("""
                with candidates as (
                    select id from saas_security_notification_outbox
                    where ((status = 'PENDING' and (next_attempt_at is null or next_attempt_at <= ?))
                        or (status = 'PROCESSING' and claimed_at <= ?))
                      and attempt_count < ?
                    order by created_at
                    for update skip locked
                    limit 20
                )
                update saas_security_notification_outbox o
                   set status = 'PROCESSING', claimed_at = ?, claim_token = ?
                  from candidates c where o.id = c.id
                returning o.id, o.idempotency_key, o.event_type, o.realm, o.username_key,
                          o.encrypted_payload, o.attempt_count
                """, (rs, row) -> new Pending(
                rs.getObject("id", UUID.class), rs.getString("idempotency_key"),
                rs.getString("event_type"), rs.getString("realm"), rs.getString("username_key"),
                rs.getString("encrypted_payload"), rs.getInt("attempt_count")),
                Timestamp.from(now), Timestamp.from(now.minus(claimLease)), maxAttempts,
                Timestamp.from(now), claimToken);
        int delivered = 0;
        for (Pending item : pending) {
            boolean accepted;
            try {
                accepted = channel.deliver(new SecurityNotificationChannel.SecurityNotification(
                        item.idempotencyKey(), item.eventType(), item.realm(), item.username(),
                        cipher.decrypt(item.encryptedPayload())));
            } catch (RuntimeException exception) {
                accepted = false;
            }
            if (accepted) {
                int updated = jdbc.update("""
                        update saas_security_notification_outbox
                           set status = 'DELIVERED', delivered_at = ?, attempt_count = attempt_count + 1,
                               next_attempt_at = null, last_error = null, claimed_at = null, claim_token = null
                         where id = ? and status = 'PROCESSING' and claim_token = ?
                        """, Timestamp.from(clock.instant()), item.id(), claimToken);
                delivered += updated;
            } else {
                int nextAttempt = item.attemptCount() + 1;
                jdbc.update("""
                        update saas_security_notification_outbox
                           set status = ?, attempt_count = attempt_count + 1, next_attempt_at = ?,
                               last_error = 'CHANNEL_UNAVAILABLE', claimed_at = null, claim_token = null
                         where id = ? and status = 'PROCESSING' and claim_token = ?
                        """, nextAttempt >= maxAttempts ? "FAILED" : "PENDING",
                        nextAttempt >= maxAttempts ? null : Timestamp.from(clock.instant().plus(retryDelay)),
                        item.id(), claimToken);
            }
        }
        return delivered;
    }

    private static Duration requirePositive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(property + " debe ser positivo");
        }
        return value;
    }

    private record Pending(UUID id, String idempotencyKey, String eventType, String realm,
                           String username, String encryptedPayload, int attemptCount) {
    }
}
