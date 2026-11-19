package com.tpverp.backend.inventory;

import com.tpverp.backend.licensing.License;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.licensing.LicenseSaasCacheAuthenticator;
import com.tpverp.backend.sync.SyncOperation;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.sync.SyncOutboxService;
import jakarta.persistence.EntityManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventorySnapshotCapture {
    public static final String ENTITY_TYPE = "INVENTORY_SNAPSHOT";
    private static final int CHUNK_ROWS = 500;
    private static final int MAX_CHUNKS = 10_000;
    private static final DateTimeFormatter CAPTURE_TIME = new DateTimeFormatterBuilder().appendInstant(6).toFormatter();

    private static final String BALANCES_FROM = """
            from existencia e
            join producto p on p.id = e.producto_id
            join almacen w on w.id = e.almacen_id and w.tienda_id = p.tienda_id
            where p.tienda_id = ?
            """;

    private final LicenseRepository licenses;
    private final LicenseSaasCacheAuthenticator authenticator;
    private final JdbcTemplate jdbc;
    private final SyncOutboxService outbox;
    private final EntityManager entityManager;

    public InventorySnapshotCapture(LicenseRepository licenses, LicenseSaasCacheAuthenticator authenticator,
            JdbcTemplate jdbc, SyncOutboxService outbox, EntityManager entityManager) {
        this.licenses = licenses;
        this.authenticator = authenticator;
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public List<Site> sites() {
        return licenses.findByActivaTrueOrderByValidaDesdeDesc().stream()
                .filter(this::isLinkedOwner)
                .map(license -> new Site(license.getLocalCompanyId(), license.getTiendaId(), license.getInstalacionId()))
                .toList();
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public int enqueue(Site site, Duration cadence) {
        var locks = jdbc.query("""
                select transaction_timestamp() as capture_time
                where pg_try_advisory_xact_lock(hashtextextended(?, 0))
                """, (row, index) -> row.getObject("capture_time", OffsetDateTime.class),
                "inventory-snapshot:" + site.storeId());
        if (locks.isEmpty()) {
            return 0;
        }

        Instant capturedAt = locks.getFirst().toInstant();
        if (!captureDue(site, capturedAt, cadence) || !stillOwned(site)) {
            return 0;
        }

        var counts = new Counts(
                jdbc.queryForObject("select count(*) from producto where tienda_id = ?", Long.class, site.storeId()),
                jdbc.queryForObject("select count(*) from almacen where tienda_id = ?", Long.class, site.storeId()),
                jdbc.queryForObject("select count(*) " + BALANCES_FROM, Long.class, site.storeId()));
        var chunks = new Chunks(site, capturedAt, counts);
        stream(site.storeId(), """
                select p.id, p.nombre, p.activo, p.product_type,
                       c.valor as code, b.valor as barcode, b2.valor as secondary_barcode,
                       f.id as family_id, f.nombre as family_name, s.id as subfamily_id, s.nombre as subfamily_name
                from producto p
                left join producto_identificador c on c.producto_id = p.id and c.tienda_id = p.tienda_id and c.tipo = 'CODIGO'
                left join producto_identificador b on b.producto_id = p.id and b.tienda_id = p.tienda_id and b.tipo = 'CODIGO_BARRAS'
                left join producto_identificador b2 on b2.producto_id = p.id and b2.tienda_id = p.tienda_id and b2.tipo = 'CODIGO_BARRAS_2'
                left join familia f on f.id = p.familia_id and f.tienda_id = p.tienda_id
                left join subfamilia s on s.id = p.subfamilia_id and s.familia_id = f.id
                where p.tienda_id = ?
                order by p.id
                """, row -> chunks.add(chunks.products, product(row)));
        stream(site.storeId(), """
                select id, nombre, activo, predeterminado from almacen where tienda_id = ? order by id
                """, row -> chunks.add(chunks.warehouses, Map.of(
                        "warehouseId", row.getString("id"), "name", requiredName(row.getString("nombre")),
                        "active", row.getBoolean("activo"), "defaultWarehouse", row.getBoolean("predeterminado"))));
        stream(site.storeId(), "select e.producto_id, e.almacen_id, e.cantidad " + BALANCES_FROM
                + " order by e.producto_id, e.almacen_id", row -> chunks.add(chunks.balances, Map.of(
                        "productId", row.getString("producto_id"), "warehouseId", row.getString("almacen_id"),
                        "quantity", row.getBigDecimal("cantidad").toPlainString())));
        chunks.finish();

        jdbc.update("""
                insert into inventory_snapshot_sync_checkpoint(store_id, source_installation_id, captured_at)
                values (?, ?, ?)
                on conflict (store_id, source_installation_id) do update set captured_at = excluded.captured_at
                """, site.storeId(), site.installationId(), locks.getFirst());
        return counts.chunkCount();
    }

    private boolean isLinkedOwner(License license) {
        return license.isSaasLinked() && license.getSaasCompanyId() != null && license.getSaasStoreId() != null
                && authenticator.isAuthentic(license);
    }

    private boolean stillOwned(Site site) {
        return licenses.findByTiendaIdAndInstalacionIdAndActivaTrue(site.storeId(), site.installationId())
                .filter(license -> site.companyId().equals(license.getLocalCompanyId()))
                .filter(this::isLinkedOwner)
                .isPresent();
    }

    private boolean captureDue(Site site, Instant capturedAt, Duration cadence) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select not exists (select from inventory_snapshot_sync_checkpoint
                    where store_id = ? and source_installation_id = ? and captured_at > ?)
                and not exists (select from sync_outbox
                    where tienda_id = ? and empresa_id = ? and tipo_entidad = 'INVENTORY_SNAPSHOT'
                      and payload ->> 'sourceInstallationId' = ? and estado in ('PENDIENTE', 'ENVIANDO', 'ERROR'))
                """, Boolean.class, site.storeId(), site.installationId(),
                OffsetDateTime.ofInstant(capturedAt.minus(cadence), ZoneOffset.UTC),
                site.storeId(), site.companyId(), site.installationId().toString()));
    }

    private void stream(UUID storeId, String sql, RowCallbackHandler consume) {
        jdbc.query(connection -> {
            var statement = connection.prepareStatement(sql);
            statement.setObject(1, storeId);
            statement.setFetchSize(CHUNK_ROWS);
            return statement;
        }, consume);
    }

    private static Map<String, Object> product(ResultSet row) throws SQLException {
        var product = new LinkedHashMap<String, Object>();
        product.put("productId", row.getString("id"));
        product.put("code", optionalIdentifier(row.getString("code")));
        product.put("name", requiredName(row.getString("nombre")));
        product.put("barcode", optionalIdentifier(row.getString("barcode")));
        product.put("secondaryBarcode", optionalIdentifier(row.getString("secondary_barcode")));
        product.put("active", row.getBoolean("activo"));
        product.put("productType", row.getString("product_type"));
        product.put("familyId", row.getString("family_id"));
        product.put("familyName", row.getString("family_name"));
        product.put("subfamilyId", row.getString("subfamily_id"));
        product.put("subfamilyName", row.getString("subfamily_name"));
        return product;
    }

    private static String optionalIdentifier(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String requiredName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("La captura de existencias incluye un nombre obligatorio vacío");
        }
        return value;
    }

    public record Site(UUID companyId, UUID storeId, UUID installationId) { }

    private record Counts(long products, long warehouses, long balances) {
        private Counts {
            if (products + warehouses + balances > (long) CHUNK_ROWS * MAX_CHUNKS) {
                throw new IllegalStateException("La captura de existencias supera 5000000 filas");
            }
        }

        private int chunkCount() {
            return Math.toIntExact(Math.max(1, Math.ceilDiv(products + warehouses + balances, CHUNK_ROWS)));
        }
    }

    private class Chunks {
        private final Site site;
        private final String capturedAt;
        private final Counts counts;
        private final UUID snapshotId = UUID.randomUUID();
        private final List<Map<String, Object>> products = new ArrayList<>();
        private final List<Map<String, Object>> warehouses = new ArrayList<>();
        private final List<Map<String, Object>> balances = new ArrayList<>();
        private int chunkIndex;
        private int size;

        Chunks(Site site, Instant capturedAt, Counts counts) {
            this.site = site;
            this.capturedAt = CAPTURE_TIME.format(capturedAt);
            this.counts = counts;
        }

        private void add(List<Map<String, Object>> kind, Map<String, Object> row) {
            kind.add(row);
            if (++size == CHUNK_ROWS) {
                enqueueChunk();
            }
        }

        private void finish() {
            if (size > 0 || chunkIndex == 0) {
                enqueueChunk();
            }
            if (chunkIndex != counts.chunkCount()) {
                throw new IllegalStateException("El número de lotes de existencias no coincide con la captura");
            }
        }

        private void enqueueChunk() {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("schemaVersion", 1);
            payload.put("snapshotId", snapshotId.toString());
            payload.put("sourceInstallationId", site.installationId().toString());
            payload.put("capturedAt", capturedAt);
            payload.put("chunkIndex", chunkIndex++);
            payload.put("chunkCount", counts.chunkCount());
            payload.put("productCount", counts.products());
            payload.put("warehouseCount", counts.warehouses());
            payload.put("balanceCount", counts.balances());
            payload.put("products", List.copyOf(products));
            payload.put("warehouses", List.copyOf(warehouses));
            payload.put("balances", List.copyOf(balances));
            outbox.enqueue(new SyncOutboundEventCommand(site.companyId(), site.storeId(), null,
                    ENTITY_TYPE, snapshotId, SyncOperation.ACTUALIZAR, payload));
            // Keep the persistence context bounded while all chunks remain in the same transaction.
            entityManager.flush();
            entityManager.clear();
            products.clear();
            warehouses.clear();
            balances.clear();
            size = 0;
        }
    }
}
