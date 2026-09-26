package com.tpverp.saas.supervision;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StoreFailureQueryService {
    private static final Set<String> SOURCES = Set.of("LOCAL_CONTROL", "LOCAL_SYNC", "LOCAL_APPLICATION", "SYNC_PROJECTION",
            "CENTRAL_SECURITY", "CENTRAL_INTEGRATION", "BOOTSTRAP");
    private static final Set<String> STATUSES = Set.of("OPEN", "REVIEWED", "RESOLVED", "DISMISSED", "ACKNOWLEDGED");
    private static final String SQL = """
            with failures as (
                select f.source || ':' || f.id::text as failure_key, f.source, f.source_id,
                       f.company_id, f.store_id, f.installation_id, f.status, f.severity, f.code,
                       f.first_seen_at, f.last_seen_at, f.occurrences,
                       f.module, f.app_version, f.trace_id, f.exception_type, f.error_location, f.received_at, f.id as stored_failure_id
                  from saas_store_failure f
                union all
                select 'CENTRAL_SECURITY:' || id::text, 'CENTRAL_SECURITY', id,
                       null::uuid, null::uuid, null::uuid,
                       case when status = 'ACKNOWLEDGED' then 'ACKNOWLEDGED' else 'OPEN' end,
                       'DANGER', 'SECURITY_DELIVERY_FAILED', created_at, coalesce(delivered_at, created_at),
                       greatest(attempt_count, 1)::bigint, null, null, null::varchar, null, null, null::timestamptz, null::uuid
                  from saas_security_notification_outbox where status in ('FAILED', 'ACKNOWLEDGED')
                union all
                select 'CENTRAL_INTEGRATION:' || r.id::text, 'CENTRAL_INTEGRATION', r.id,
                       e.company_id, null::uuid, null::uuid,
                       case when r.status = 'ACKNOWLEDGED' then 'ACKNOWLEDGED' else 'OPEN' end,
                       'DANGER', 'INTEGRATION_DELIVERY_FAILED', r.started_at, coalesce(r.completed_at, r.started_at),
                       greatest(r.delivery_attempt_count, 1)::bigint, null, null, null::varchar, null, null, null::timestamptz, null::uuid
                  from saas_integration_run r join saas_integration_endpoint e on e.id = r.integration_id
                 where r.status in ('FAILED', 'ACKNOWLEDGED') and r.delivery_attempt_count > 0
                union all
                select 'BOOTSTRAP:' || b.id::text, 'BOOTSTRAP', b.id,
                       b.company_id, null::uuid, null::uuid, 'OPEN', 'WARNING',
                       'MEMBER_CATEGORY_BOOTSTRAP_STALLED', b.created_at, b.last_activity_at, 1::bigint,
                       null, null, null::varchar, null, null, null::timestamptz, null::uuid
                  from saas_member_category_bootstrap b
                 where b.status in ('COLLECTING', 'CONFLICT') and b.last_activity_at <= ?
            )
            select f.*, c.name as company_name, s.name as store_name, s.internal_code, s.active as store_active,
                   i.installation_id as public_installation_id, i.installation_reference
              from failures f
              left join saas_company c on c.id = f.company_id
              left join saas_store s on s.id = f.store_id
              left join saas_installation i on i.id = f.installation_id
             where 1 = 1
            """;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration bootstrapInactivity;

    public StoreFailureQueryService(JdbcTemplate jdbc, Clock clock,
            @Value("${tpv.saas.operational-incidents.category-bootstrap-inactivity:PT1H}") Duration bootstrapInactivity) {
        this.jdbc = jdbc; this.clock = clock; this.bootstrapInactivity = bootstrapInactivity;
    }

    @Transactional(readOnly = true)
    public Page page(Filter filter, String cursor, int size) {
        if (size < 1 || size > 200) throw invalid("size debe estar entre 1 y 200");
        validate(filter);
        StringBuilder sql = new StringBuilder(SQL);
        List<Object> args = arguments();
        append(sql, args, filter);
        if (cursor != null && !cursor.isBlank()) {
            Cursor after = decode(cursor);
            sql.append(" and (f.last_seen_at < ? or (f.last_seen_at = ? and f.failure_key > ?))");
            args.add(Timestamp.from(after.at())); args.add(Timestamp.from(after.at())); args.add(after.key());
        }
        sql.append(" order by f.last_seen_at desc, f.failure_key asc limit ?");
        args.add(size + 1);
        List<StoreFailureView> values = jdbc.query(sql.toString(), this::view, args.toArray());
        boolean more = values.size() > size;
        List<StoreFailureView> items = List.copyOf(values.subList(0, Math.min(values.size(), size)));
        return new Page(items, more ? encode(items.getLast()) : null, more, items.size());
    }

    @Transactional(readOnly = true)
    public StoreFailureView detail(String key) {
        validateKey(key);
        List<Object> args = arguments(); args.add(key);
        return jdbc.query(SQL + " and f.failure_key = ?", this::view, args.toArray()).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Fallo operativo no encontrado"));
    }

    private List<Object> arguments() {
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.from(clock.instant().minus(bootstrapInactivity)));
        return args;
    }

    private static void validate(Filter value) {
        if (value.source() != null && !SOURCES.contains(value.source())) throw invalid("Origen no soportado");
        if (value.status() != null && !STATUSES.contains(value.status())) throw invalid("Estado no soportado");
        if (value.from() != null && value.to() != null && !value.from().isBefore(value.to())) throw invalid("Intervalo de fechas invalido");
        if (value.q() != null && value.q().length() > 200) throw invalid("Busqueda demasiado larga");
    }

    private static void append(StringBuilder sql, List<Object> args, Filter f) {
        equal(sql, args, "f.company_id", f.companyId());
        equal(sql, args, "f.store_id", f.storeId());
        equal(sql, args, "i.installation_id", f.installationId());
        equal(sql, args, "f.source", f.source());
        equal(sql, args, "f.status", f.status());
        // Central/company incidents have no store attribution and remain visible.
        if (f.activeStoresOnly()) sql.append(" and (f.store_id is null or s.active = true)");
        if (f.from() != null) { sql.append(" and f.last_seen_at >= ?"); args.add(Timestamp.from(f.from())); }
        if (f.to() != null) { sql.append(" and f.last_seen_at < ?"); args.add(Timestamp.from(f.to())); }
        if (f.q() != null && !f.q().isBlank()) {
            String pattern = "%" + f.q().trim().toLowerCase(java.util.Locale.ROOT)
                    .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            sql.append(" and (lower(concat_ws(' ', c.name, s.name, s.internal_code, i.installation_reference, i.installation_id::text, f.code, f.source_id::text, f.trace_id, f.module)) like ? escape '!'"
                    + " or exists (select 1 from saas_store_failure_trace t where t.failure_id = f.stored_failure_id and lower(t.trace_id) like ? escape '!'))");
            args.add(pattern); args.add(pattern);
        }
    }

    private static void equal(StringBuilder sql, List<Object> args, String column, Object value) {
        if (value != null) { sql.append(" and ").append(column).append(" = ?"); args.add(value); }
    }

    private StoreFailureView view(ResultSet rs, int row) throws SQLException {
        UUID store = rs.getObject("store_id", UUID.class);
        String code = rs.getString("code");
        return new StoreFailureView(rs.getString("failure_key"), rs.getString("source"), rs.getObject("source_id", UUID.class),
                rs.getObject("company_id", UUID.class), rs.getString("company_name"), store, rs.getString("store_name"),
                rs.getString("internal_code"), rs.getObject("public_installation_id", UUID.class), rs.getString("installation_reference"),
                rs.getString("status"), rs.getString("severity"), code, safeDetail(rs.getString("source"), code),
                rs.getTimestamp("first_seen_at").toInstant(), rs.getTimestamp("last_seen_at").toInstant(),
                rs.getLong("occurrences"), store == null, rs.getObject("store_active", Boolean.class),
                rs.getString("module"), rs.getString("app_version"), rs.getString("trace_id"),
                rs.getString("exception_type"), rs.getString("error_location"),
                rs.getTimestamp("received_at") == null ? null : rs.getTimestamp("received_at").toInstant());
    }

    private static String safeDetail(String source, String code) {
        return switch (source) {
            case "LOCAL_CONTROL" -> "Alerta de control comunicada por la tienda: " + code;
            case "LOCAL_APPLICATION" -> "Error de aplicacion comunicado por la tienda; consultar el modulo y la referencia de diagnostico.";
            case "LOCAL_SYNC" -> "Un evento de la cola local no pudo entregarse; consultar su referencia en la tienda.";
            case "SYNC_PROJECTION" -> "Un evento recibido no pudo proyectarse; consultar el evento de sincronizacion.";
            case "CENTRAL_SECURITY" -> "Fallo de entrega de una notificacion de seguridad del servidor central.";
            case "CENTRAL_INTEGRATION" -> "Fallo de entrega de una integracion del servidor central.";
            case "BOOTSTRAP" -> "Proceso central de categorias de socios pendiente por inactividad o conflicto.";
            default -> code;
        };
    }

    private static String encode(StoreFailureView value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((value.lastSeenAt() + "|" + value.id()).getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decode(String value) {
        try {
            if (value.length() > 256) throw invalid("Cursor invalido");
            String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 2) throw invalid("Cursor invalido");
            validateKey(parts[1]);
            return new Cursor(Instant.parse(parts[0]), parts[1]);
        } catch (RuntimeException failure) { throw invalid("Cursor invalido"); }
    }

    private static void validateKey(String key) {
        try {
            String[] parts = key.split(":", -1);
            if (parts.length != 2 || !SOURCES.contains(parts[0])) throw invalid("Referencia invalida");
            UUID.fromString(parts[1]);
        } catch (RuntimeException failure) { throw invalid("Referencia invalida"); }
    }

    private static ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private record Cursor(Instant at, String key) { }
    public record Filter(UUID companyId, UUID storeId, UUID installationId, String source, String status,
            Instant from, Instant to, boolean activeStoresOnly, String q) { }
    public record Page(List<StoreFailureView> items, String nextCursor, boolean hasMore, int size) { }
}
