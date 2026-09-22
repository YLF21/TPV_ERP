package com.tpverp.saas.supervision;

import com.tpverp.saas.sync.SaasSyncEvent;
import com.tpverp.saas.sync.SyncOperation;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StoreFailureProjector {
    public static final String ENTITY_TYPE = "STORE_FAILURE";
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public StoreFailureProjector(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    public boolean supports(String entityType, SyncOperation operation) {
        return ENTITY_TYPE.equals(entityType) && operation == SyncOperation.ACTUALIZAR;
    }

    @Transactional
    public void project(SaasSyncEvent event, Map<String, Object> payload) {
        StoreFailureSnapshot value = StoreFailureSnapshot.parse(payload, clock.instant());
        var installation = event.getInstallation();
        if (installation == null || event.getStore() == null
                || !installation.getInstallationId().equals(value.installationId())
                || !installation.getCompany().getId().equals(event.getCompany().getId())
                || !installation.getStore().getId().equals(event.getStore().getId())
                || !event.getEntityId().equals(value.sourceId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Procedencia operativa incompatible con la instalacion autenticada");
        }
        // The installation already exists: serialises first inserts and revision checks.
        jdbc.queryForObject("select id from saas_installation where id = ? for update", UUID.class, installation.getId());
        var previous = jdbc.query("""
                select id, source_revision, source_hash from saas_store_failure
                 where installation_id = ? and source = ? and source_id = ?
                """, (rs, row) -> new Previous(rs.getObject("id", UUID.class), rs.getLong("source_revision"), rs.getString("source_hash")),
                installation.getId(), value.source(), value.sourceId());
        if (!previous.isEmpty()) {
            Previous current = previous.getFirst();
            if (value.sourceRevision() < current.revision()) return;
            if (value.sourceRevision() == current.revision()) {
                if (!event.getPayloadHash().equals(current.hash())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Misma revision operativa con contenido diferente");
                }
                return;
            }
            jdbc.update("""
                    update saas_store_failure set source_revision = ?, source_hash = ?, status = ?, severity = ?, code = ?,
                        first_seen_at = least(first_seen_at, ?), last_seen_at = greatest(last_seen_at, ?),
                        received_at = ?, occurrences = greatest(occurrences, ?), last_event_id = ? where id = ?
                    """, value.sourceRevision(), event.getPayloadHash(), value.status(), value.severity(), value.code(),
                    time(value.firstSeenAt()), time(value.lastSeenAt()), time(event.getReceivedAt()), value.occurrences(), event.getEventId(), current.id());
        } else {
            jdbc.update("""
                    insert into saas_store_failure
                    (id, company_id, store_id, installation_id, source, source_id, source_revision, source_hash,
                     status, severity, code, first_seen_at, last_seen_at, received_at, occurrences, last_event_id)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), event.getCompany().getId(), event.getStore().getId(), installation.getId(),
                    value.source(), value.sourceId(), value.sourceRevision(), event.getPayloadHash(), value.status(), value.severity(), value.code(),
                    time(value.firstSeenAt()), time(value.lastSeenAt()), time(event.getReceivedAt()), value.occurrences(), event.getEventId());
        }
    }

    /** Called in the receiver transaction: rollback semantics are inherited from the source projection. */
    public void recordProjection(SaasSyncEvent event, Instant now) {
        if (ENTITY_TYPE.equals(event.getEntityType())) return; // reporting must never report its own failure recursively
        if (event.getProjectionStatus() == SaasSyncEvent.ProjectionStatus.ERROR) {
            jdbc.update("""
                    insert into saas_store_failure
                    (id, company_id, store_id, installation_id, source, source_id, source_revision,
                     status, severity, code, first_seen_at, last_seen_at, received_at, occurrences, last_event_id)
                    values (?, ?, ?, ?, 'SYNC_PROJECTION', ?, 0, 'OPEN', 'DANGER', 'PROJECTION_FAILED', ?, ?, ?, 1, ?)
                    on conflict (id) do update set status = 'OPEN', last_seen_at = excluded.last_seen_at,
                        received_at = excluded.received_at, occurrences = saas_store_failure.occurrences + 1
                    """, event.getEventId(), event.getCompany().getId(), event.getStore() == null ? null : event.getStore().getId(),
                    event.getInstallation() == null ? null : event.getInstallation().getId(), event.getEventId(), time(now), time(now), time(now), event.getEventId());
        } else if (event.getProjectionStatus() == SaasSyncEvent.ProjectionStatus.PROJECTED
                || event.getProjectionStatus() == SaasSyncEvent.ProjectionStatus.IGNORED) {
            jdbc.update("update saas_store_failure set status = 'RESOLVED', last_seen_at = ?, received_at = ? where id = ? and source = 'SYNC_PROJECTION' and status <> 'RESOLVED'",
                    time(now), time(now), event.getEventId());
        }
    }

    private static Timestamp time(Instant value) { return Timestamp.from(value); }
    private record Previous(UUID id, long revision, String hash) { }
}
