package com.tpverp.backend.inventory;

import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.application.PermissionChecks;
import org.springframework.security.core.context.SecurityContextHolder;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores informational requests only. Stock is read through the view query, never written. */
@Service
@Transactional
public class ReplenishmentService {
    private final NamedParameterJdbcTemplate jdbc;
    private final CurrentOrganization organization;
    private final ProductRepository products;
    private final WarehouseRepository warehouses;

    public ReplenishmentService(NamedParameterJdbcTemplate jdbc, CurrentOrganization organization,
            ProductRepository products, WarehouseRepository warehouses) {
        this.jdbc = jdbc; this.organization = organization; this.products = products; this.warehouses = warehouses;
    }

    private static final String SELECT = """
        select r.*, w.nombre warehouse_name, p.nombre product_name,
               case when :includePurchaseFields then p.precio_compra else null end precio_compra,
               coalesce(c.valor, '') code, coalesce(b.valor, '') barcode,
               coalesce(e.cantidad, 0) stock, s.id supplier_id,
               coalesce(nullif(s.nombre_comercial, ''), s.razon_social, '') supplier_name,
               p.familia_id, coalesce(f.nombre, '') family_name,
               p.subfamilia_id, coalesce(sf.nombre, '') subfamily_name
        from replenishment_request r
        join producto p on p.id = r.product_id and p.tienda_id = r.store_id
        join almacen w on w.id = r.warehouse_id and w.tienda_id = r.store_id
        left join producto_identificador c on c.producto_id = p.id and c.tipo = 'CODIGO'
        left join producto_identificador b on b.producto_id = p.id and b.tipo = 'CODIGO_BARRAS'
        left join existencia e on e.producto_id = p.id and e.almacen_id = w.id
        left join producto_proveedor ps on ps.producto_id = p.id and ps.principal = true
        left join proveedor s on s.id = ps.proveedor_id
        left join familia f on f.id = p.familia_id
        left join subfamilia sf on sf.id = p.subfamilia_id
        where r.store_id = :storeId
        """;

    @Transactional(readOnly = true)
    static List<UUID> single(UUID id) {
        return id == null ? List.of() : List.of(id);
    }

    public List<ReplenishmentView> list(UUID warehouseId, UUID supplierId, UUID familyId,
            UUID subfamilyId, String search, List<UUID> ids) {
        return listFiltered(single(warehouseId), single(supplierId), single(familyId), single(subfamilyId), search, ids);
    }

    public List<ReplenishmentView> listFiltered(List<UUID> warehouseIds, List<UUID> supplierIds,
            List<UUID> familyIds, List<UUID> subfamilyIds, String search, List<UUID> ids) {
        var params = new MapSqlParameterSource("storeId", organization.currentStore().getId())
                .addValue("includePurchaseFields", PermissionChecks.hasPurchaseDocumentRead(
                        SecurityContextHolder.getContext().getAuthentication()));
        var sql = new StringBuilder(SELECT);
        if (warehouseIds != null && !warehouseIds.isEmpty()) { sql.append(" and r.warehouse_id in (:warehouseIds)"); params.addValue("warehouseIds", warehouseIds); }
        if (supplierIds != null && !supplierIds.isEmpty()) { sql.append(" and s.id in (:supplierIds)"); params.addValue("supplierIds", supplierIds); }
        if (familyIds != null && !familyIds.isEmpty()) { sql.append(" and p.familia_id in (:familyIds)"); params.addValue("familyIds", familyIds); }
        if (subfamilyIds != null && !subfamilyIds.isEmpty()) { sql.append(" and p.subfamilia_id in (:subfamilyIds)"); params.addValue("subfamilyIds", subfamilyIds); }
        if (search != null && !search.isBlank()) {
            if (search.length() > 120) throw new IllegalArgumentException("La búsqueda supera 120 caracteres");
            sql.append(" and (lower(p.nombre) like :search escape '!' or lower(coalesce(c.valor,'')) like :search escape '!' or lower(coalesce(b.valor,'')) like :search escape '!')");
            params.addValue("search", "%" + search.trim().toLowerCase(Locale.ROOT)
                    .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        if (ids != null) {
            if (ids.isEmpty()) return List.of();
            if (ids.size() > 5000) throw new IllegalArgumentException("Seleccione como máximo 5000 solicitudes");
            sql.append(" and r.id in (:ids)"); params.addValue("ids", ids);
        }
        sql.append(" order by r.created_at desc, r.id limit 50001");
        var result = jdbc.query(sql.toString(), params, ReplenishmentService::view);
        if (result.size() > 50000) throw new IllegalArgumentException("Hay más de 50000 solicitudes. Aplique filtros.");
        return result;
    }

    public void create(UUID productId, UUID warehouseId, BigDecimal quantity, String reason) {
        validate(quantity, reason);
        UUID storeId = organization.currentStore().getId();
        var product = products.findById(productId).filter(p -> p.getStoreId().equals(storeId))
                .orElseThrow(() -> new IllegalArgumentException("Producto no válido"));
        var warehouse = warehouses.findById(warehouseId).filter(w -> w.getStoreId().equals(storeId) && w.isActive())
                .orElseThrow(() -> new IllegalArgumentException("Almacén no válido"));
        jdbc.update("""
            insert into replenishment_request(id, store_id, product_id, warehouse_id, quantity, reason)
            values (:id, :storeId, :productId, :warehouseId, :quantity, :reason)
            """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("storeId", storeId)
                .addValue("productId", product.getId()).addValue("warehouseId", warehouse.getId())
                .addValue("quantity", quantity).addValue("reason", reason.trim()));
    }

    public void edit(UUID id, long version, BigDecimal quantity, String reason) {
        validate(quantity, reason);
        var params = itemParams(new Item(id, version)).addValue("quantity", quantity).addValue("reason", reason.trim());
        requireChanged(jdbc.update("""
            update replenishment_request set quantity = :quantity, reason = :reason, version = version + 1
            where id = :id and store_id = :storeId and version = :version and status = 'PENDING'
            """, params));
    }

    public void changeStatus(List<Item> items, boolean ordered) {
        validateItems(items);
        var store = organization.currentStore();
        var date = LocalDate.now(ZoneId.of(store.getTimezone()));
        // A deterministic lock order avoids deadlocks between overlapping multi-selections.
        for (var item : items.stream().sorted(Comparator.comparing(Item::id)).toList()) {
            var params = itemParams(item).addValue("ordered", ordered)
                    .addValue("date", java.sql.Date.valueOf(date));
            requireChanged(jdbc.update("""
                update replenishment_request set
                  order_date = case when :ordered then coalesce(order_date, :date) else null end,
                  status = case when :ordered then 'ORDERED' else 'PENDING' end,
                  version = version + 1
                where id = :id and store_id = :storeId and version = :version
                """, params));
        }
    }

    public void delete(List<Item> items) {
        validateItems(items);
        for (var item : items.stream().sorted(Comparator.comparing(Item::id)).toList()) {
            requireChanged(jdbc.update("delete from replenishment_request where id = :id and store_id = :storeId and version = :version", itemParams(item)));
        }
    }

    private MapSqlParameterSource itemParams(Item item) {
        if (item.id() == null || item.version() < 0) throw new IllegalArgumentException("Solicitud no válida");
        return new MapSqlParameterSource("storeId", organization.currentStore().getId())
                .addValue("id", item.id()).addValue("version", item.version());
    }

    static void validate(BigDecimal quantity, String reason) {
        if (quantity == null || quantity.signum() <= 0 || quantity.stripTrailingZeros().scale() > 3
                || quantity.compareTo(new BigDecimal("10000000000000000")) >= 0)
            throw new IllegalArgumentException("La cantidad debe ser positiva y tener como máximo tres decimales");
        if (reason == null || reason.isBlank() || reason.length() > 500)
            throw new IllegalArgumentException("El motivo es obligatorio y admite hasta 500 caracteres");
    }

    private static void validateItems(List<Item> items) {
        if (items == null || items.isEmpty() || items.size() > 5000)
            throw new IllegalArgumentException("Seleccione entre 1 y 5000 solicitudes");
        var seen = new HashSet<UUID>();
        for (var item : items) if (item == null || item.id() == null || item.version() < 0 || !seen.add(item.id()))
            throw new IllegalArgumentException("La selección contiene solicitudes no válidas o repetidas");
    }

    private static void requireChanged(int count) { if (count != 1) throw new ReplenishmentConflictException(); }

    private static ReplenishmentView view(ResultSet rs, int row) throws SQLException {
        return new ReplenishmentView(rs.getObject("id", UUID.class), rs.getLong("version"),
                rs.getObject("product_id", UUID.class), rs.getObject("warehouse_id", UUID.class),
                rs.getString("warehouse_name"), rs.getString("code"), rs.getString("barcode"),
                rs.getString("product_name"), rs.getBigDecimal("quantity"), rs.getString("reason"),
                rs.getBigDecimal("stock"), rs.getBigDecimal("precio_compra"), rs.getObject("supplier_id", UUID.class),
                rs.getString("supplier_name"), rs.getObject("familia_id", UUID.class), rs.getString("family_name"),
                rs.getObject("subfamilia_id", UUID.class), rs.getString("subfamily_name"), rs.getString("status"),
                rs.getObject("order_date", LocalDate.class));
    }

    public record Item(UUID id, long version) {}
}
