package com.tpverp.saas.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReplenishmentRequestProjector {
    public static final String ENTITY_TYPE = "REPLENISHMENT_REQUEST";
    public static final String PURGED_PAYLOAD = "{\"purged\":\"REPLENISHMENT_REQUEST_V1\"}";
    private static final int MAX_METADATA_BYTES = 16_384;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ReplenishmentRequestProjector(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    public boolean supports(String entityType) {
        return ENTITY_TYPE.equals(entityType);
    }

    public void project(SaasSyncEvent event, SyncEventRequest request) {
        if (event.getStore() == null || event.getInstallation() == null
                || !event.getStore().getCompany().getId().equals(event.getCompany().getId())
                || !event.getInstallation().getCompany().getId().equals(event.getCompany().getId())
                || !event.getInstallation().getStore().getId().equals(event.getStore().getId())) {
            throw conflict("Procedencia de reposicion incompatible");
        }
        Map<String, Object> payload = request.payload();
        if (!BigDecimal.ONE.equals(number(payload.get("schemaVersion"), "schemaVersion"))
                || !request.entityId().equals(uuid(payload.get("requestId"), "requestId"))) {
            throw invalid("Esquema o requestId de reposicion invalido");
        }
        long revision;
        try {
            revision = number(payload.get("revision"), "revision").longValueExact();
        } catch (ArithmeticException exception) {
            throw invalid("revision invalida");
        }
        if (revision < 0) throw invalid("revision invalida");
        if (request.operation() != SyncOperation.CREAR && request.operation() != SyncOperation.ACTUALIZAR
                && request.operation() != SyncOperation.BORRAR) {
            throw invalid("Operacion de reposicion no soportada");
        }

        Snapshot snapshot = request.operation() == SyncOperation.BORRAR ? null : parse(payload);
        UUID companyId = event.getCompany().getId();
        UUID storeId = event.getStore().getId();
        UUID requestId = request.entityId();
        // Serialize first insertion as well as updates for this tenant/store/request key.
        jdbc.queryForObject("select 1 from (select pg_advisory_xact_lock(hashtextextended(?::text, 0))) lock", Integer.class,
                companyId + ":" + storeId + ":" + requestId);
        var previous = jdbc.query("select revision, payload_hash from saas_replenishment_request "
                        + "where company_id=? and store_id=? and request_id=? for update",
                (rs, row) -> new Fence(rs.getLong(1), rs.getString(2)), companyId, storeId, requestId);
        if (!previous.isEmpty()) {
            Fence fence = previous.getFirst();
            if (revision < fence.revision()) return;
            if (revision == fence.revision()) {
                if (!event.getPayloadHash().equals(fence.hash())) {
                    throw conflict("revision de reposicion reutilizada con contenido diferente");
                }
                return;
            }
        }
        Instant now = clock.instant();
        String state = snapshot == null ? "DELETED" : snapshot.status();
        if (snapshot != null && "ORDERED".equals(state) && !snapshot.expiresAt().isAfter(now)) {
            state = "EXPIRED";
            snapshot = null;
        }
        if (previous.isEmpty()) {
            jdbc.update("insert into saas_replenishment_request "
                            + "(company_id,store_id,request_id,revision,payload_hash,state,product_id,warehouse_id,quantity,reason,order_date,created_at,expires_at,metadata_json) "
                            + "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)",
                    companyId, storeId, requestId, revision, event.getPayloadHash(), state,
                    snapshot == null ? null : snapshot.productId(), snapshot == null ? null : snapshot.warehouseId(),
                    snapshot == null ? null : snapshot.quantity(), snapshot == null ? null : snapshot.reason(),
                    snapshot == null ? null : snapshot.orderDate(), timestamp(snapshot == null ? null : snapshot.createdAt()),
                    timestamp(snapshot == null ? null : snapshot.expiresAt()), snapshot == null ? null : snapshot.metadata());
        } else {
            jdbc.update("update saas_replenishment_request set revision=?,payload_hash=?,state=?,product_id=?,warehouse_id=?,quantity=?,reason=?,order_date=?,created_at=?,expires_at=?,metadata_json=?::jsonb "
                            + "where company_id=? and store_id=? and request_id=?",
                    revision, event.getPayloadHash(), state,
                    snapshot == null ? null : snapshot.productId(), snapshot == null ? null : snapshot.warehouseId(),
                    snapshot == null ? null : snapshot.quantity(), snapshot == null ? null : snapshot.reason(),
                    snapshot == null ? null : snapshot.orderDate(), timestamp(snapshot == null ? null : snapshot.createdAt()),
                    timestamp(snapshot == null ? null : snapshot.expiresAt()), snapshot == null ? null : snapshot.metadata(),
                    companyId, storeId, requestId);
        }
    }

    /** Removes old pre-projector payloads after a newer accepted revision supersedes them. */
    public void purgeSupersededEvents(SaasSyncEvent current, SyncEventRequest request) {
        long latestRevision = number(request.payload().get("revision"), "revision").longValueExact();
        var rows = jdbc.queryForList("select event_id,payload,payload_hash from saas_sync_event "
                        + "where company_id=? and store_id=? and installation_id=? and entity_type=? and entity_id=? "
                        + "and event_id<>? and projection_status in ('IGNORED','PROJECTED') "
                        + "and projection_error is null and payload<>? for update",
                current.getCompany().getId(), current.getStore().getId(), current.getInstallation().getId(), ENTITY_TYPE,
                request.entityId(), current.getEventId(), PURGED_PAYLOAD);
        for (var row : rows) {
            String raw = (String) row.get("payload");
            try {
                var payload = mapper.readTree(raw);
                if (!payload.isObject() || !payload.path("requestId").asText().equals(request.entityId().toString())
                        || payload.path("schemaVersion").asInt(-1) != 1
                        || !payload.path("revision").isIntegralNumber()
                        || !payload.path("revision").canConvertToLong()) continue;
                long oldRevision = payload.path("revision").longValue();
                if (oldRevision > latestRevision || oldRevision == latestRevision
                        && !current.getPayloadHash().equals(row.get("payload_hash"))) continue;
                String hash = java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256")
                                .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                if (!hash.equals(row.get("payload_hash"))) continue;
                jdbc.update("update saas_sync_event set payload=?,projection_status='PROJECTED',"
                                + "projected_at=?,projection_error=null where event_id=?",
                        PURGED_PAYLOAD, Timestamp.from(clock.instant()), row.get("event_id"));
            } catch (JsonProcessingException | java.security.NoSuchAlgorithmException ignored) {
                // Never discard an unverified original event.
            }
        }
    }

    private Snapshot parse(Map<String, Object> payload) {
        UUID productId = uuid(payload.get("productId"), "productId");
        UUID warehouseId = uuid(payload.get("warehouseId"), "warehouseId");
        BigDecimal quantity = decimal(payload.get("quantity"), "quantity");
        if (quantity.signum() <= 0 || quantity.scale() > 3 || quantity.precision() - quantity.scale() > 16) {
            throw invalid("quantity fuera de rango");
        }
        Object reasonValue = payload.get("reason");
        if (!(reasonValue instanceof String reason) || reason.isBlank() || reason.length() > 500) {
            throw invalid("reason invalido");
        }
        String status = string(payload.get("status"), "status");
        if (!status.equals("PENDING") && !status.equals("ORDERED")) throw invalid("status invalido");
        Instant createdAt = instant(payload.get("createdAt"), "createdAt");
        LocalDate orderDate = payload.get("orderDate") == null ? null : date(payload.get("orderDate"));
        Instant expiresAt = payload.get("expiresAt") == null ? null : instant(payload.get("expiresAt"), "expiresAt");
        if (status.equals("ORDERED") && (orderDate == null || expiresAt == null)
                || status.equals("PENDING") && (orderDate != null || expiresAt != null)) {
            throw invalid("Fechas de reposicion incompatibles con status");
        }
        Object metadata = payload.getOrDefault("metadata", Map.of());
        if (!(metadata instanceof Map<?, ?>)) throw invalid("metadata invalida");
        String metadataJson;
        try {
            metadataJson = mapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw invalid("metadata invalida");
        }
        if (metadataJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_METADATA_BYTES) {
            throw invalid("metadata demasiado grande");
        }
        return new Snapshot(productId, warehouseId, quantity, (String) reasonValue, status,
                orderDate, createdAt, expiresAt, metadataJson);
    }

    private static UUID uuid(Object value, String field) {
        try {
            if (value instanceof String text) return UUID.fromString(text);
        } catch (IllegalArgumentException ignored) { }
        throw invalid(field + " invalido");
    }

    private static String string(Object value, String field) {
        if (value instanceof String text) return text;
        throw invalid(field + " invalido");
    }

    private static BigDecimal number(Object value, String field) {
        if (!(value instanceof Number)) throw invalid(field + " invalido");
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException exception) {
            throw invalid(field + " invalido");
        }
    }

    private static BigDecimal decimal(Object value, String field) {
        if (value instanceof String text && text.matches("[0-9]+(\\.[0-9]+)?")) {
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException exception) {
                throw invalid(field + " invalido");
            }
        }
        return number(value, field);
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Object value, String field) {
        try {
            return Instant.parse(string(value, field));
        } catch (DateTimeParseException exception) {
            throw invalid(field + " invalido");
        }
    }

    private static LocalDate date(Object value) {
        try {
            return LocalDate.parse(string(value, "orderDate"));
        } catch (DateTimeParseException exception) {
            throw invalid("orderDate invalido");
        }
    }

    private static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    private record Snapshot(UUID productId, UUID warehouseId, BigDecimal quantity, String reason,
                            String status, LocalDate orderDate, Instant createdAt, Instant expiresAt, String metadata) { }
    private record Fence(long revision, String hash) { }
}
