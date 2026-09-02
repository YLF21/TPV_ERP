package com.tpverp.saas.admin;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class IntegrationOutboxDispatcher {

    private final JdbcTemplate jdbc;
    private final IntegrationSecretCipher cipher;
    private final IntegrationDeliveryChannel channel;
    private final Clock clock;
    private final Duration retryDelay;
    private final Duration claimLease;
    private final int maxAttempts;

    public IntegrationOutboxDispatcher(
            JdbcTemplate jdbc, IntegrationSecretCipher cipher, IntegrationDeliveryChannel channel, Clock clock,
            @Value("${tpv.saas.integrations.retry-delay:PT5M}") Duration retryDelay,
            @Value("${tpv.saas.integrations.claim-lease:PT2M}") Duration claimLease,
            @Value("${tpv.saas.integrations.max-delivery-attempts:8}") int maxAttempts) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.channel = channel;
        this.clock = clock;
        this.retryDelay = requirePositive(retryDelay, "retry-delay");
        this.claimLease = requirePositive(claimLease, "claim-lease");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("max-delivery-attempts debe ser positivo");
        }
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${tpv.saas.integrations.poll-delay:60000}")
    public int dispatchPending() {
        Instant now = clock.instant();
        UUID claimToken = UUID.randomUUID();
        List<Pending> rows = jdbc.query("""
                with candidates as (
                    select r.id, e.integration_type, e.target_url, e.api_key_encrypted, e.api_key
                      from saas_integration_run r
                      join saas_integration_endpoint e on e.id = r.integration_id
                     where ((r.status = 'PENDING' and (r.next_attempt_at is null or r.next_attempt_at <= ?))
                        or (r.status = 'PROCESSING' and r.claimed_at <= ?))
                       and r.delivery_attempt_count < ?
                     order by r.started_at
                     for update of r skip locked
                     limit 20
                )
                update saas_integration_run r
                   set status = 'PROCESSING', claimed_at = ?, claim_token = ?
                  from candidates c where r.id = c.id
                returning r.id, r.integration_id, r.idempotency_key, r.payload,
                          r.delivery_attempt_count, c.integration_type, c.target_url,
                          c.api_key_encrypted, c.api_key
                """, (rs, row) -> new Pending(
                rs.getObject("id", UUID.class), rs.getObject("integration_id", UUID.class),
                rs.getString("idempotency_key"), rs.getString("payload"),
                rs.getString("integration_type"), rs.getString("target_url"),
                rs.getString("api_key_encrypted"), rs.getString("api_key"),
                rs.getInt("delivery_attempt_count")),
                Timestamp.from(now), Timestamp.from(now.minus(claimLease)), maxAttempts,
                Timestamp.from(now), claimToken);
        int delivered = 0;
        for (Pending row : rows) {
            boolean accepted;
            try {
                accepted = channel.deliver(new IntegrationDeliveryChannel.IntegrationDelivery(
                        row.integrationId(), row.integrationType(), row.targetUrl(),
                        row.encryptedApiKey() == null ? row.legacyApiKey() : cipher.decrypt(row.encryptedApiKey()),
                        row.payload(), row.idempotencyKey()));
            } catch (RuntimeException exception) {
                accepted = false;
            }
            if (accepted) {
                Instant completed = clock.instant();
                int updated = jdbc.update("""
                        update saas_integration_run set status = 'SUCCEEDED', completed_at = ?,
                            delivery_attempt_count = delivery_attempt_count + 1, next_attempt_at = null,
                            error_code = null, error_message = null, claimed_at = null, claim_token = null
                        where id = ? and status = 'PROCESSING' and claim_token = ?
                        """, Timestamp.from(completed), row.id(), claimToken);
                if (updated == 1) {
                    jdbc.update("update saas_integration_endpoint set last_sync_at = ? where id = ?",
                            Timestamp.from(completed), row.integrationId());
                    delivered++;
                }
            } else {
                int nextAttempt = row.deliveryAttemptCount() + 1;
                jdbc.update("""
                        update saas_integration_run set status = ?,
                            delivery_attempt_count = delivery_attempt_count + 1, next_attempt_at = ?,
                            error_code = 'CHANNEL_UNAVAILABLE', error_message = 'Canal de entrega no disponible',
                            claimed_at = null, claim_token = null, completed_at = ?
                        where id = ? and status = 'PROCESSING' and claim_token = ?
                        """, nextAttempt >= maxAttempts ? "FAILED" : "PENDING",
                        nextAttempt >= maxAttempts ? null : Timestamp.from(clock.instant().plus(retryDelay)),
                        nextAttempt >= maxAttempts ? Timestamp.from(clock.instant()) : null,
                        row.id(), claimToken);
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

    private record Pending(UUID id, UUID integrationId, String idempotencyKey, String payload,
                           String integrationType, String targetUrl, String encryptedApiKey,
                           String legacyApiKey, int deliveryAttemptCount) {
    }
}
