package com.tpverp.backend.supervision.repair;

import com.tpverp.backend.supervision.StoreFailurePublisher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class StoreRemoteRepairService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public StoreRemoteRepairService(JdbcTemplate jdbc, Clock clock, PlatformTransactionManager transactions) {
        this.jdbc = jdbc; this.clock = clock;
        transaction = new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(5);
    }

    /** Command acceptance and outbox reopening commit together, before any network acknowledgement. */
    public RemoteRepairResult accept(UUID installationId, List<AuthorizedSite> sites, RemoteRepairCommand command) {
        return transaction.execute(status -> {
            Instant now = clock.instant();
            AuthorizedSite site = matchingSite(installationId, command.companyId(), command.storeId(), sites);
            int inserted = jdbc.update("""
                    insert into store_remote_repair(command_id,installation_id,saas_company_id,saas_store_id,event_id,
                        action,expected_version,expires_at,payload_fingerprint,local_company_id,local_store_id,
                        status,result_code,created_at,checked_at,updated_at)
                    values (?,?,?,?,?,?,?,?,?,?,?,'FAILED','RETRY_FAILED',?,?,?) on conflict (command_id) do nothing
                    """, command.commandId(), installationId, command.companyId(), command.storeId(), command.eventId(),
                    command.action(), command.expectedVersion(), timestamp(command.expiresAt()), fingerprint(command),
                    site == null ? null : site.local().companyId(), site == null ? null : site.local().storeId(),
                    timestamp(now), timestamp(now), timestamp(now));
            Ledger row = locked(command.commandId());
            if (!row.installationId().equals(installationId) || !row.fingerprint().equals(fingerprint(command))) {
                // Do not mutate the genuine ledger or event when a command ID is replayed with another payload/scope.
                throw new IllegalStateException("REMOTE_REPAIR_IMMUTABLE_COMMAND_CONFLICT");
            }
            if (inserted == 0) return reconcile(row, sites, now);
            if (!now.isBefore(command.expiresAt())) return update(row, "FAILED", "REPAIR_EXPIRED", now);
            if (site == null) return update(row, "FAILED", "EVENT_NOT_FOUND", now);
            if (!"RETRY_SYNC_OUTBOX".equals(command.action())) return update(row, "FAILED", "UNSUPPORTED_ACTION", now);
            if (!lockCurrentAuthorization(row)) return update(row, "FAILED", "EVENT_NOT_FOUND", clock.instant());
            Event event = lockedEvent(row);
            if (event == null) return update(row, "FAILED", "EVENT_NOT_FOUND", now);
            if ("STORE_FAILURE".equals(event.entityType())) return update(row, "FAILED", "UNSUPPORTED_ACTION", now);
            if (event.version() != command.expectedVersion() || !"DEAD_LETTER".equals(event.status())) {
                return update(row, "FAILED", "STALE_EVENT", now);
            }
            // Row-lock waits can cross the deadline. Recheck the clock immediately before executing the action.
            Instant executionTime = clock.instant();
            if (!executionTime.isBefore(command.expiresAt())) return update(row, "FAILED", "REPAIR_EXPIRED", executionTime);
            // Same transition as SyncOutboxEvent.reopenForManualRetry, including optimistic version advancement.
            // Attempts, failure history, payload and event ID are intentionally retained.
            jdbc.update("""
                    update sync_outbox set estado='PENDIENTE',proximo_intento_en=?,reclamado_en=null,
                        claim_token=null,actualizado_en=?,version=version+1
                     where event_id=? and empresa_id=? and tienda_id=?
                    """, timestamp(executionTime), timestamp(executionTime), row.eventId(), row.localCompanyId(), row.localStoreId());
            return update(row, "RUNNING", "RETRY_QUEUED", executionTime);
        });
    }

    /** Pending results survive failed HTTP reports and restarts; RUNNING commands are checked fairly in bounded batches. */
    public List<UUID> pending(UUID installationId) {
        return jdbc.query("""
                select command_id from store_remote_repair where installation_id=?
                    and (status='RUNNING' or reported_status is distinct from status or reported_result_code is distinct from result_code)
                 order by checked_at,command_id limit 50
                """, (rs, index) -> rs.getObject(1, UUID.class), installationId);
    }

    public RemoteRepairResult refresh(UUID installationId, UUID commandId, List<AuthorizedSite> sites) {
        return transaction.execute(status -> {
            Ledger row = locked(commandId);
            if (row == null || !row.installationId().equals(installationId)) return null;
            return reconcile(row, sites, clock.instant());
        });
    }

    public void acknowledge(RemoteRepairResult sent) {
        jdbc.update("""
                update store_remote_repair set reported_status=?,reported_result_code=?
                 where command_id=? and installation_id=? and status=? and result_code=?
                """, sent.status(), sent.resultCode(), sent.commandId(), sent.installationId(), sent.status(), sent.resultCode());
    }

    private RemoteRepairResult reconcile(Ledger row, List<AuthorizedSite> sites, Instant now) {
        if (!"RUNNING".equals(row.status())) return update(row, row.status(), row.resultCode(), now);
        AuthorizedSite site = matchingSite(row.installationId(), row.saasCompanyId(), row.saasStoreId(), sites);
        if (site == null || !site.local().companyId().equals(row.localCompanyId()) || !site.local().storeId().equals(row.localStoreId())) {
            return update(row, "FAILED", "EVENT_NOT_FOUND", now);
        }
        Event event = lockedEvent(row);
        now = clock.instant();
        if (event == null) return update(row, "FAILED", "EVENT_NOT_FOUND", now);
        if ("ENVIADO".equals(event.status())) return update(row, "SUCCEEDED", "SYNC_DELIVERED", now);
        if ("DEAD_LETTER".equals(event.status())) return update(row, "FAILED", "RETRY_FAILED", now);
        if (!now.isBefore(row.expiresAt())) return update(row, "FAILED", "REPAIR_EXPIRED", now);
        return update(row, "RUNNING", "RETRY_QUEUED", now);
    }

    private RemoteRepairResult update(Ledger row, String status, String code, Instant now) {
        jdbc.update("""
                update store_remote_repair set status=?,result_code=?,checked_at=?,
                    updated_at=case when status<>? or result_code<>? then ? else updated_at end where command_id=?
                """, status, code, timestamp(now), status, code, timestamp(now), row.commandId());
        return new RemoteRepairResult(row.commandId(), row.installationId(), status, code);
    }

    private Ledger locked(UUID id) {
        return jdbc.query("select * from store_remote_repair where command_id=? for update",
                StoreRemoteRepairService::ledger, id).stream().findFirst().orElse(null);
    }

    private Event lockedEvent(Ledger row) {
        return jdbc.query("""
                select estado,version,tipo_entidad from sync_outbox
                 where event_id=? and empresa_id=? and tienda_id=? for update
                """, (rs, index) -> new Event(rs.getString(1), rs.getLong(2), rs.getString(3)),
                row.eventId(), row.localCompanyId(), row.localStoreId()).stream().findFirst().orElse(null);
    }

    /** Recheck live authority without cached JPA entities. Share locks serialize revocation/rebinding with execution.
     * Lock order is ledger, license/store, outbox; license writers never lock this repair ledger. */
    private boolean lockCurrentAuthorization(Ledger row) {
        return jdbc.query("""
                select l.id from licencia l join tienda t on t.id=l.tienda_id
                 where l.activa=true and l.instalacion_id=? and l.tienda_id=? and t.empresa_id=?
                   and lower(btrim(l.import_metadata->>'saasCompanyId'))=?
                   and lower(btrim(l.import_metadata->>'saasStoreId'))=?
                 for share of l,t
                """, (rs, index) -> rs.getObject(1, UUID.class), row.installationId(), row.localStoreId(),
                row.localCompanyId(), row.saasCompanyId().toString(), row.saasStoreId().toString()).size() == 1;
    }

    private static AuthorizedSite matchingSite(UUID installationId, UUID companyId, UUID storeId, List<AuthorizedSite> sites) {
        var matches = sites.stream().filter(site -> site.local().installationId().equals(installationId)
                && site.saasCompanyId().equals(companyId) && site.saasStoreId().equals(storeId)).distinct().limit(2).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static Ledger ledger(ResultSet rs, int index) throws SQLException {
        return new Ledger(rs.getObject("command_id", UUID.class), rs.getObject("installation_id", UUID.class),
                rs.getObject("saas_company_id", UUID.class), rs.getObject("saas_store_id", UUID.class),
                rs.getObject("event_id", UUID.class), rs.getString("payload_fingerprint"),
                rs.getObject("local_company_id", UUID.class), rs.getObject("local_store_id", UUID.class),
                rs.getTimestamp("expires_at").toInstant(), rs.getString("status"), rs.getString("result_code"));
    }

    private static String fingerprint(RemoteRepairCommand command) {
        try {
            String value = command.companyId() + "|" + command.storeId() + "|" + command.action() + "|"
                    + command.eventId() + "|" + command.expectedVersion() + "|" + command.expiresAt();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("REMOTE_REPAIR_HASH_UNAVAILABLE"); }
    }

    private static Timestamp timestamp(Instant instant) { return Timestamp.from(instant.truncatedTo(ChronoUnit.MICROS)); }
    public record AuthorizedSite(StoreFailurePublisher.Site local, UUID saasCompanyId, UUID saasStoreId) { }
    private record Event(String status, long version, String entityType) { }
    private record Ledger(UUID commandId, UUID installationId, UUID saasCompanyId, UUID saasStoreId, UUID eventId,
            String fingerprint, UUID localCompanyId, UUID localStoreId, Instant expiresAt, String status, String resultCode) { }
}
