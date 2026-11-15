package com.tpverp.backend.inventory;

import com.tpverp.backend.sync.SyncOperation;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.sync.SyncOutboxService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Publishes informational snapshots only; never changes stock or product data. */
@Service
public class ReplenishmentSyncPublisher {
    public static final String ENTITY_TYPE = "REPLENISHMENT_REQUEST";
    private final NamedParameterJdbcTemplate jdbc;
    private final SyncOutboxService outbox;
    private final ApplicationEventPublisher events;

    public ReplenishmentSyncPublisher(NamedParameterJdbcTemplate jdbc, SyncOutboxService outbox,
            ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.events = events;
    }

    private static final String SNAPSHOT = """
        select r.*, t.empresa_id, t.timezone, w.nombre warehouse_name, p.nombre product_name,
               p.precio_compra, coalesce(c.valor, '') code, coalesce(b.valor, '') barcode,
               coalesce(e.cantidad, 0) stock, s.id supplier_id,
               coalesce(nullif(s.nombre_comercial, ''), s.razon_social, '') supplier_name,
               p.familia_id, coalesce(f.nombre, '') family_name,
               p.subfamilia_id, coalesce(sf.nombre, '') subfamily_name
        from replenishment_request r
        join tienda t on t.id = r.store_id
        join producto p on p.id = r.product_id and p.tienda_id = r.store_id
        join almacen w on w.id = r.warehouse_id and w.tienda_id = r.store_id
        left join producto_identificador c on c.producto_id = p.id and c.tipo = 'CODIGO'
        left join producto_identificador b on b.producto_id = p.id and b.tipo = 'CODIGO_BARRAS'
        left join existencia e on e.producto_id = p.id and e.almacen_id = w.id
        left join producto_proveedor ps on ps.producto_id = p.id and ps.principal = true
        left join proveedor s on s.id = ps.proveedor_id
        left join familia f on f.id = p.familia_id
        left join subfamilia sf on sf.id = p.subfamilia_id
        """;

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(UUID storeId, UUID requestId, SyncOperation operation) {
        if (operation != SyncOperation.CREAR && operation != SyncOperation.ACTUALIZAR) {
            throw new IllegalArgumentException("Operacion de reposicion no valida");
        }
        var params = new MapSqlParameterSource("storeId", storeId).addValue("id", requestId);
        var snapshots = jdbc.query(SNAPSHOT + " where r.store_id = :storeId and r.id = :id for update of r",
                params, ReplenishmentSyncPublisher::snapshot);
        if (snapshots.size() != 1) throw new IllegalStateException("Solicitud de reposicion no encontrada");
        var snapshot = snapshots.getFirst();
        enqueue(snapshot.companyId(), storeId, requestId, operation, snapshot.payload());
        jdbc.update("""
            update replenishment_request set sync_enqueued_revision = version
            where store_id = :storeId and id = :id
            """, params);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deleted(UUID storeId, UUID requestId, long revision) {
        UUID companyId = jdbc.queryForObject("select empresa_id from tienda where id = :storeId",
                Map.of("storeId", storeId), UUID.class);
        // Remove business data even from dead-letter/claimed snapshots. An already
        // in-flight older message is harmless: SaaS retains the deletion revision.
        // Cleanup and the final deletion event commit or roll back together.
        outbox.discardReplenishmentSnapshots(companyId, storeId, requestId);
        enqueue(companyId, storeId, requestId, SyncOperation.BORRAR,
                Map.of("schemaVersion", 1, "requestId", requestId.toString(), "revision", revision));
    }

    /** Existing requests are copied once, safely alongside concurrent user changes. */
    @Transactional
    public int backfill(int batchSize) {
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("Lote de reposicion no valido");
        var rows = jdbc.query("""
            select id, store_id from replenishment_request
            where sync_enqueued_revision is null or sync_enqueued_revision < version
            order by id limit :limit for update skip locked
            """, Map.of("limit", batchSize), (rs, row) ->
                    new RequestKey(rs.getObject("store_id", UUID.class), rs.getObject("id", UUID.class)));
        for (var row : rows) publish(row.storeId(), row.id(), SyncOperation.ACTUALIZAR);
        return rows.size();
    }

    private void enqueue(UUID companyId, UUID storeId, UUID id, SyncOperation operation, Map<String, Object> payload) {
        var event = outbox.enqueue(new SyncOutboundEventCommand(companyId, storeId, null,
                ENTITY_TYPE, id, operation, payload));
        events.publishEvent(new ReplenishmentSyncRequested(event.getEventId()));
    }

    private static Snapshot snapshot(ResultSet rs, int row) throws SQLException {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("schemaVersion", 1);
        payload.put("requestId", rs.getObject("id", UUID.class).toString());
        payload.put("revision", rs.getLong("version"));
        payload.put("productId", rs.getObject("product_id", UUID.class).toString());
        payload.put("warehouseId", rs.getObject("warehouse_id", UUID.class).toString());
        // Strings keep all 19 significant digits across untyped JSON payloads.
        payload.put("quantity", rs.getBigDecimal("quantity").toPlainString());
        payload.put("reason", rs.getString("reason"));
        payload.put("status", rs.getString("status"));
        LocalDate orderDate = rs.getObject("order_date", LocalDate.class);
        payload.put("orderDate", orderDate == null ? null : orderDate.toString());
        payload.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
        payload.put("expiresAt", orderDate == null ? null : orderDate.plusMonths(1)
                .atStartOfDay(ZoneId.of(rs.getString("timezone"))).toInstant().toString());
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("code", rs.getString("code"));
        metadata.put("barcode", rs.getString("barcode"));
        metadata.put("name", rs.getString("product_name"));
        metadata.put("warehouseName", rs.getString("warehouse_name"));
        metadata.put("stock", rs.getBigDecimal("stock").toPlainString());
        metadata.put("purchasePrice", rs.getBigDecimal("precio_compra").toPlainString());
        metadata.put("supplierId", nullableUuid(rs, "supplier_id"));
        metadata.put("supplierName", rs.getString("supplier_name"));
        metadata.put("familyId", nullableUuid(rs, "familia_id"));
        metadata.put("familyName", rs.getString("family_name"));
        metadata.put("subfamilyId", nullableUuid(rs, "subfamilia_id"));
        metadata.put("subfamilyName", rs.getString("subfamily_name"));
        payload.put("metadata", metadata);
        return new Snapshot(rs.getObject("empresa_id", UUID.class), payload);
    }

    private static String nullableUuid(ResultSet rs, String field) throws SQLException {
        UUID value = rs.getObject(field, UUID.class);
        return value == null ? null : value.toString();
    }

    private record Snapshot(UUID companyId, Map<String, Object> payload) { }
    private record RequestKey(UUID storeId, UUID id) { }
}
