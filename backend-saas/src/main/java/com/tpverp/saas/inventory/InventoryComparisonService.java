package com.tpverp.saas.inventory;

import static com.tpverp.saas.inventory.InventoryComparison.*;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class InventoryComparisonService {
    private final NamedParameterJdbcTemplate jdbc;

    public InventoryComparisonService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Capture(long key, InventoryReport.Snapshot snapshot) {}

    private Capture capture(MapSqlParameterSource params, UUID id) {
        var found =
                jdbc.query(
                        """
                        select s.*,i.active current_installation from saas_inventory_snapshot s
                        join saas_installation i on i.id=s.installation_id
                        where s.company_id=:company and s.store_id=:store and s.snapshot_id=:snapshot and s.completed_at is not null
                        """,
                        params.addValue("snapshot", id),
                        (rs, n) ->
                                new Capture(rs.getLong("id"), InventoryReportService.snapshot(rs)));
        if (found.isEmpty())
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Captura completa no encontrada");
        return found.getFirst();
    }

    private static final String BALANCES =
            """
            with previous as (select * from saas_inventory_balance where snapshot=:previous),
            current as (select * from saas_inventory_balance where snapshot=:current),
            compared as (
              select coalesce(a.product_id,b.product_id) product_id,coalesce(a.warehouse_id,b.warehouse_id) warehouse_id,
                coalesce(c.code,p.code) code,coalesce(c.name,p.name) name,
                coalesce(cw.name,pw.name) warehouse_name, b.quantity previous_quantity,a.quantity current_quantity,
                case when c.product_type=p.product_type then a.quantity-b.quantity end delta,
                case when b.product_id is null then 'ADDED' when a.product_id is null then 'REMOVED'
                     when a.quantity<>b.quantity or c.product_type<>p.product_type then 'CHANGED' else 'UNCHANGED' end status,
                concat_ws(chr(31),p.code,p.name,p.barcode,p.secondary_barcode,c.code,c.name,c.barcode,c.secondary_barcode) terms
              from previous b full join current a using(product_id,warehouse_id)
              left join saas_inventory_product p on p.snapshot=b.snapshot and p.product_id=b.product_id
              left join saas_inventory_product c on c.snapshot=a.snapshot and c.product_id=a.product_id
              left join saas_inventory_warehouse pw on pw.snapshot=b.snapshot and pw.warehouse_id=b.warehouse_id
              left join saas_inventory_warehouse cw on cw.snapshot=a.snapshot and cw.warehouse_id=a.warehouse_id
            )
            """;

    public InventoryComparison compare(
            UUID companyId,
            UUID storeId,
            UUID previousSnapshotId,
            UUID currentSnapshotId,
            UUID warehouseId,
            String search,
            boolean changedOnly,
            Sort sort,
            Direction direction,
            int page,
            int size) {
        return readComparison(
                companyId,
                storeId,
                previousSnapshotId,
                currentSnapshotId,
                warehouseId,
                search,
                changedOnly,
                sort,
                direction,
                page,
                size,
                200);
    }

    public InventoryComparison exportComparison(
            UUID companyId,
            UUID storeId,
            UUID previousSnapshotId,
            UUID currentSnapshotId,
            UUID warehouseId,
            String search,
            boolean changedOnly,
            Sort sort,
            Direction direction,
            int limit) {
        return readComparison(
                companyId,
                storeId,
                previousSnapshotId,
                currentSnapshotId,
                warehouseId,
                search,
                changedOnly,
                sort,
                direction,
                0,
                limit,
                50_001);
    }

    private InventoryComparison readComparison(
            UUID companyId,
            UUID storeId,
            UUID previousSnapshotId,
            UUID currentSnapshotId,
            UUID warehouseId,
            String search,
            boolean changedOnly,
            Sort sort,
            Direction direction,
            int page,
            int size,
            int maximumSize) {
        if (page < 0 || size < 1 || size > maximumSize || search != null && search.length() > 255)
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Filtros de comparación no válidos");
        var params =
                new MapSqlParameterSource()
                        .addValue("company", companyId)
                        .addValue("store", storeId)
                        .addValue("size", size)
                        .addValue("offset", (long) page * size);
        var previous = capture(params, previousSnapshotId);
        var current = capture(params, currentSnapshotId);
        if (!previous.snapshot.installationId().equals(current.snapshot.installationId()))
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Las capturas pertenecen a instalaciones diferentes");
        if (previousSnapshotId.equals(currentSnapshotId)
                || previous.snapshot.capturedAt().isAfter(current.snapshot.capturedAt()))
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Selecciona dos capturas distintas en orden cronológico");
        params.addValue("previous", previous.key).addValue("current", current.key);
        String source = " from compared where true";
        if (warehouseId != null) {
            params.addValue("warehouse", warehouseId);
            source += " and warehouse_id=:warehouse";
        }
        if (search != null && !search.isBlank()) {
            params.addValue("search", search.strip());
            source += " and strpos(lower(terms),lower(:search))>0";
        }
        if (changedOnly) source += " and status<>'UNCHANGED'";
        var counts =
                jdbc.queryForObject(
                        BALANCES
                                + """
                                select count(*) filter(where status='ADDED'),count(*) filter(where status='REMOVED'),
                                       count(*) filter(where status='CHANGED'),count(*) filter(where status='UNCHANGED')
                                """
                                + source,
                        params,
                        (rs, n) ->
                                new Counts(
                                        rs.getLong(1),
                                        rs.getLong(2),
                                        rs.getLong(3),
                                        rs.getLong(4)));
        var catalogue =
                jdbc.queryForObject(
                        """
                        select count(*) filter(where p.product_id is null),count(*) filter(where c.product_id is null),
                               count(*) filter(where c.active and not p.active),count(*) filter(where p.active and not c.active),
                               count(*) filter(where c.product_id is not null and p.product_id is not null and
                                 (c.code,c.name,c.barcode,c.secondary_barcode,c.product_type,c.family_id,c.family_name,c.subfamily_id,c.subfamily_name)
                                 is distinct from
                                 (p.code,p.name,p.barcode,p.secondary_barcode,p.product_type,p.family_id,p.family_name,p.subfamily_id,p.subfamily_name))
                        from (select * from saas_inventory_product where snapshot=:previous) p
                        full join (select * from saas_inventory_product where snapshot=:current) c using(product_id)
                        """,
                        params,
                        (rs, n) ->
                                new Catalogue(
                                        rs.getLong(1),
                                        rs.getLong(2),
                                        rs.getLong(3),
                                        rs.getLong(4),
                                        rs.getLong(5)));
        String order =
                switch (sort) {
                    case PRODUCT -> "lower(name)";
                    case WAREHOUSE -> "lower(warehouse_name)";
                    case STATUS -> "status";
                    case DELTA -> "delta";
                };
        var rows =
                jdbc.query(
                        BALANCES
                                + """
                                select *, round(delta * 100 / nullif(abs(previous_quantity),0), 2) percent
                                """
                                + source
                                + " order by "
                                + order
                                + " "
                                + direction.name()
                                + " nulls last, product_id,warehouse_id limit :size offset :offset",
                        params,
                        (rs, n) ->
                                new Row(
                                        rs.getObject("product_id", UUID.class),
                                        rs.getString("code"),
                                        rs.getString("name"),
                                        rs.getObject("warehouse_id", UUID.class),
                                        rs.getString("warehouse_name"),
                                        rs.getString("status"),
                                        rs.getString("previous_quantity"),
                                        rs.getString("current_quantity"),
                                        rs.getString("delta"),
                                        rs.getString("percent")));
        return new InventoryComparison(
                previous.snapshot,
                current.snapshot,
                page,
                size,
                counts.added() + counts.removed() + counts.changed() + counts.unchanged(),
                counts,
                catalogue,
                rows);
    }
}
