package com.tpverp.backend.supervision;

import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.sync.SyncOperation;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.sync.SyncOutboxService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bounded snapshots of committed operational state; transport/retries use the existing durable outbox. */
@Service
public class StoreFailurePublisher {
    public static final String ENTITY_TYPE = "STORE_FAILURE";
    private static final int SOURCE_BATCH_SIZE = 50;
    private final JdbcTemplate jdbc;
    private final LicenseRepository licenses;
    private final SyncOutboxService outbox;

    public StoreFailurePublisher(JdbcTemplate jdbc, LicenseRepository licenses, SyncOutboxService outbox) {
        this.jdbc = jdbc; this.licenses = licenses; this.outbox = outbox;
    }

    @Transactional(readOnly = true)
    public List<Site> sites() {
        Map<UUID, Site> result = new LinkedHashMap<>();
        for (var license : licenses.findByActivaTrueOrderByValidaDesdeDesc()) {
            if (license.getSaasCompanyId() != null && license.getSaasStoreId() != null) {
                result.putIfAbsent(license.getTiendaId(), new Site(license.getLocalCompanyId(), license.getTiendaId(), license.getInstalacionId()));
            }
        }
        return List.copyOf(result.values());
    }

    @Transactional
    public int publish(Site site) {
        // Avoid duplicate snapshots from concurrent scheduler instances without holding sale/control locks.
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select pg_try_advisory_xact_lock(hashtext(?))", Boolean.class,
                "store-failure:" + site.storeId()))) return 0;
        List<Signal> control = jdbc.query("""
                select a.id as source_id, a.version as source_revision,
                       case a.estado when 'NEW' then 'OPEN' when 'REVIEWED' then 'REVIEWED'
                            when 'CLOSED' then 'RESOLVED' else 'DISMISSED' end as status,
                       case when a.prioridad in ('HIGH','CRITICAL') then 'DANGER'
                            when a.prioridad = 'INFORMATIONAL' then 'INFO' else 'WARNING' end as severity,
                       e.tipo as code, a.creada_en as first_seen_at, a.actualizada_en as last_seen_at,
                       1::bigint as occurrences
                  from control_alerta a join control_evento e on e.id = a.evento_id and e.tienda_id = a.tienda_id
                 where a.tienda_id = ? and not exists (
                    select 1 from sync_outbox sent
                     where sent.tienda_id = a.tienda_id and sent.tipo_entidad = 'STORE_FAILURE'
                       and sent.entidad_id = a.id and sent.payload ->> 'source' = 'LOCAL_CONTROL'
                       and sent.payload ->> 'installationId' = ?
                       and (sent.payload ->> 'sourceRevision')::bigint >= a.version
                       and sent.estado <> 'DEAD_LETTER')
                 order by a.actualizada_en, a.id limit ?
                """, (rs, row) -> signal(rs, "LOCAL_CONTROL"), site.storeId(), site.installationId().toString(), SOURCE_BATCH_SIZE);
        List<Signal> sync = jdbc.query("""
                select original.event_id as source_id, original.version as source_revision,
                       case when original.estado = 'ENVIADO' then 'RESOLVED' else 'OPEN' end as status,
                       case when original.estado = 'DEAD_LETTER' then 'DANGER' else 'WARNING' end as severity,
                       'SYNC_DELIVERY_FAILED' as code,
                       original.first_failure_at as first_seen_at,
                       original.actualizado_en as last_seen_at,
                       original.failure_count as occurrences
                  from sync_outbox original
                 where original.empresa_id = ? and original.tienda_id = ?
                   and original.tipo_entidad <> 'STORE_FAILURE'
                   and original.failure_count > 0 and original.first_failure_at is not null
                   and original.estado in ('ERROR','DEAD_LETTER','ENVIADO')
                   and not exists (
                    select 1 from sync_outbox sent
                     where sent.tienda_id = original.tienda_id and sent.tipo_entidad = 'STORE_FAILURE'
                       and sent.entidad_id = original.event_id and sent.payload ->> 'source' = 'LOCAL_SYNC'
                       and sent.payload ->> 'installationId' = ?
                       and (sent.payload ->> 'sourceRevision')::bigint >= original.version
                       and sent.estado <> 'DEAD_LETTER')
                 order by original.actualizado_en, original.event_id limit ?
                """, (rs, row) -> signal(rs, "LOCAL_SYNC"), site.companyId(), site.storeId(), site.installationId().toString(), SOURCE_BATCH_SIZE);
        for (Signal signal : control) enqueue(site, signal);
        for (Signal signal : sync) enqueue(site, signal);
        // Every application revision carries a customer-visible trace. Retry the original
        // durable event, including older revisions, rather than replacing it with only the latest.
        int replayed = jdbc.update("""
                update sync_outbox set estado = 'PENDIENTE', intentos = 0, proximo_intento_en = now(),
                    reclamado_en = null, claim_token = null, actualizado_en = now(), version = version + 1
                 where id in (select id from sync_outbox
                    where empresa_id = ? and tienda_id = ? and tipo_entidad = 'STORE_FAILURE'
                      and payload ->> 'source' = 'LOCAL_APPLICATION'
                      and payload ->> 'installationId' = ? and estado = 'DEAD_LETTER'
                    order by actualizado_en, id limit ? for update skip locked)
                """, site.companyId(), site.storeId(), site.installationId().toString(), SOURCE_BATCH_SIZE);
        List<Map<String, Object>> application = jdbc.query("""
                select original.* from local_application_failure original
                 where original.empresa_id = ? and original.tienda_id = ? and original.instalacion_id = ?
                   and not exists (
                    select 1 from sync_outbox sent
                     where sent.empresa_id = original.empresa_id and sent.tienda_id = original.tienda_id
                       and sent.tipo_entidad = 'STORE_FAILURE' and sent.entidad_id = original.id
                       and sent.payload ->> 'source' = 'LOCAL_APPLICATION'
                       and sent.payload ->> 'installationId' = ?
                       and (sent.payload ->> 'sourceRevision')::bigint >= original.revision
                       and sent.estado <> 'DEAD_LETTER')
                 order by original.last_seen_at, original.id limit ?
                """, (rs, row) -> applicationPayload(rs, site.installationId()), site.companyId(), site.storeId(), site.installationId(), site.installationId().toString(), SOURCE_BATCH_SIZE);
        for (Map<String, Object> evidence : application) {
            outbox.enqueue(new SyncOutboundEventCommand(site.companyId(), site.storeId(), null, ENTITY_TYPE,
                    UUID.fromString((String) evidence.get("sourceId")), SyncOperation.ACTUALIZAR, evidence));
        }
        return control.size() + sync.size() + application.size() + replayed;
    }

    static Map<String, Object> applicationPayload(ResultSet rs, UUID installationId) throws SQLException {
        Signal signal = new Signal("LOCAL_APPLICATION", rs.getObject("id", UUID.class), rs.getLong("revision"),
                "OPEN", "DANGER", "APPLICATION_ERROR", rs.getTimestamp("first_seen_at").toInstant(),
                rs.getTimestamp("last_seen_at").toInstant(), rs.getLong("occurrences"));
        Map<String, Object> result = new LinkedHashMap<>(payload(installationId, signal));
        result.put("schemaVersion", 2);
        result.put("module", rs.getString("module"));
        result.put("appVersion", rs.getString("app_version"));
        result.put("traceId", rs.getString("trace_id"));
        result.put("exceptionType", rs.getString("exception_type"));
        result.put("errorLocation", rs.getString("error_location"));
        return result;
    }

    private void enqueue(Site site, Signal signal) {
        outbox.enqueue(new SyncOutboundEventCommand(site.companyId(), site.storeId(), null,
                ENTITY_TYPE, signal.sourceId(), SyncOperation.ACTUALIZAR, payload(site.installationId(), signal)));
    }

    static Map<String, Object> payload(UUID installationId, Signal signal) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("installationId", installationId.toString());
        result.put("source", signal.source());
        result.put("sourceId", signal.sourceId().toString());
        result.put("sourceRevision", signal.revision());
        result.put("status", signal.status());
        result.put("severity", signal.severity());
        result.put("code", signal.code());
        result.put("firstSeenAt", signal.firstSeenAt().toString());
        result.put("lastSeenAt", signal.lastSeenAt().toString());
        result.put("occurrences", signal.occurrences());
        return Map.copyOf(result);
    }

    private static Signal signal(ResultSet rs, String source) throws SQLException {
        return new Signal(source, rs.getObject("source_id", UUID.class), rs.getLong("source_revision"),
                rs.getString("status"), rs.getString("severity"), rs.getString("code"),
                rs.getTimestamp("first_seen_at").toInstant(), rs.getTimestamp("last_seen_at").toInstant(), rs.getLong("occurrences"));
    }

    public record Site(UUID companyId, UUID storeId, UUID installationId) { }
    record Signal(String source, UUID sourceId, long revision, String status, String severity,
            String code, Instant firstSeenAt, Instant lastSeenAt, long occurrences) { }
}
