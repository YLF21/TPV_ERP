package com.tpverp.saas.admin;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OutboxOperationsService {

    private final JdbcTemplate jdbc;
    private final AdminAuditService audit;
    private final Clock clock;

    public OutboxOperationsService(JdbcTemplate jdbc, AdminAuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OutboxFailurePageResponse failures(String requestedChannel, int limit, String requestedCursor) {
        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit debe estar entre 1 y 100");
        }
        String channel = channel(requestedChannel);
        FailureCursor cursor = cursor(requestedCursor);
        StringBuilder sql = new StringBuilder("""
                select id, channel, subject, attempts, error, failed_at
                from (
                    select id, 'SECURITY' as channel, event_type as subject,
                           attempt_count as attempts, 'DELIVERY_FAILED' as error, created_at as failed_at
                      from saas_security_notification_outbox where status = 'FAILED'
                    union all
                    select id, 'INTEGRATION' as channel, integration_id::text as subject,
                           delivery_attempt_count as attempts,
                           coalesce(nullif(error_code, ''), 'DELIVERY_FAILED') as error,
                           coalesce(completed_at, started_at) as failed_at
                      from saas_integration_run
                     where status = 'FAILED' and delivery_attempt_count > 0
                ) failures where 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (channel != null) {
            sql.append(" and channel = ?");
            args.add(channel);
        }
        if (cursor != null) {
            sql.append(" and (failed_at > ? or (failed_at = ? and id > ?))");
            args.add(Timestamp.from(cursor.failedAt()));
            args.add(Timestamp.from(cursor.failedAt()));
            args.add(cursor.id());
        }
        sql.append(" order by failed_at, id limit ?");
        args.add(limit + 1);
        List<OutboxFailureResponse> rows = jdbc.query(sql.toString(), (rs, row) -> new OutboxFailureResponse(
                rs.getObject("id", UUID.class), rs.getString("channel"),
                rs.getString("subject"), rs.getInt("attempts"), rs.getString("error"),
                rs.getTimestamp("failed_at").toInstant()), args.toArray());
        boolean hasMore = rows.size() > limit;
        List<OutboxFailureResponse> items = hasMore ? rows.subList(0, limit) : rows;
        OutboxFailureResponse last = items.isEmpty() ? null : items.get(items.size() - 1);
        return new OutboxFailurePageResponse(items, hasMore ? encode(last) : null);
    }

    @Transactional
    public void requeueSecurity(UUID id, String reason) {
        String resolution = normalized(reason);
        int updated = jdbc.update("""
                update saas_security_notification_outbox
                   set status = 'PENDING', attempt_count = 0, next_attempt_at = ?,
                       claimed_at = null, claim_token = null, last_error = null
                 where id = ? and status = 'FAILED'
                """, Timestamp.from(clock.instant()), id);
        requireUpdated(updated, "Notificacion de seguridad FAILED no encontrada");
        audit.log("REQUEUE_SECURITY_OUTBOX", "SECURITY_NOTIFICATION_OUTBOX", id.toString(), resolution);
    }

    @Transactional
    public void acknowledgeSecurity(UUID id, String reason) {
        String resolution = normalized(reason);
        int updated = jdbc.update("""
                update saas_security_notification_outbox
                   set status = 'ACKNOWLEDGED', next_attempt_at = null,
                       claimed_at = null, claim_token = null,
                       last_error = left(?, 500)
                 where id = ? and status = 'FAILED'
                """, "ACK: " + resolution, id);
        requireUpdated(updated, "Notificacion de seguridad FAILED no encontrada");
        audit.log("ACK_SECURITY_OUTBOX", "SECURITY_NOTIFICATION_OUTBOX", id.toString(), resolution);
    }

    @Transactional
    public void requeueIntegration(UUID id, String reason) {
        String resolution = normalized(reason);
        int updated = jdbc.update("""
                update saas_integration_run
                   set status = 'PENDING', delivery_attempt_count = 0, next_attempt_at = ?,
                       completed_at = null, claimed_at = null, claim_token = null,
                       error_code = null, error_message = null
                 where id = ? and status = 'FAILED' and delivery_attempt_count > 0
                """, Timestamp.from(clock.instant()), id);
        requireUpdated(updated, "Entrega de integracion FAILED no encontrada");
        audit.log("REQUEUE_INTEGRATION_OUTBOX", "INTEGRATION_RUN", id.toString(), resolution);
    }

    @Transactional
    public void acknowledgeIntegration(UUID id, String reason) {
        String resolution = normalized(reason);
        int updated = jdbc.update("""
                update saas_integration_run
                   set status = 'ACKNOWLEDGED', next_attempt_at = null,
                       claimed_at = null, claim_token = null, completed_at = ?,
                       error_code = 'ACKNOWLEDGED', error_message = left(?, 500)
                 where id = ? and status = 'FAILED' and delivery_attempt_count > 0
                """, Timestamp.from(clock.instant()), resolution, id);
        requireUpdated(updated, "Entrega de integracion FAILED no encontrada");
        audit.log("ACK_INTEGRATION_OUTBOX", "INTEGRATION_RUN", id.toString(), resolution);
    }

    private static void requireUpdated(int updated, String message) {
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, message);
        }
    }

    private static String normalized(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El motivo operativo es obligatorio");
        }
        String value = reason.trim();
        if (value.length() < 5 || value.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El motivo operativo debe tener entre 5 y 500 caracteres");
        }
        return value;
    }

    private static String channel(String requested) {
        if (requested == null || requested.isBlank()) {
            return null;
        }
        String value = requested.trim().toUpperCase(Locale.ROOT);
        if (!value.equals("SECURITY") && !value.equals("INTEGRATION")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "channel no soportado");
        }
        return value;
    }

    private static FailureCursor cursor(String requested) {
        if (requested == null || requested.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(requested), StandardCharsets.UTF_8);
            int separator = decoded.indexOf('|');
            if (separator <= 0 || separator == decoded.length() - 1) {
                throw new IllegalArgumentException("cursor");
            }
            return new FailureCursor(Instant.parse(decoded.substring(0, separator)),
                    UUID.fromString(decoded.substring(separator + 1)));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cursor invalido");
        }
    }

    private static String encode(OutboxFailureResponse value) {
        String raw = value.failedAt().toString() + "|" + value.id();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private record FailureCursor(Instant failedAt, UUID id) {
    }
}
