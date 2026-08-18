package com.tpverp.backend.inventory;

import com.tpverp.backend.catalog.DiscountType;
import com.tpverp.backend.catalog.PriceUseMode;
import com.tpverp.backend.catalog.ProductType;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StockPageOrderRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public StockPageOrderRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<UUID> findProductIds(
            UUID storeId,
            String search,
            ProductType productType,
            PriceUseMode priceUseMode,
            DiscountType discountType,
            boolean offersOnly,
            UUID familyId,
            UUID taxId,
            Boolean offerActive,
            UUID warehouseId,
            String sortBy,
            String sortDirection,
            UUID cursorId,
            int limit) {
        return findProductIds(
                storeId, search, productType, priceUseMode, discountType, offersOnly,
                familyId, taxId, offerActive, null, null, warehouseId,
                sortBy, sortDirection, cursorId, limit);
    }

    public List<UUID> findProductIds(
            UUID storeId,
            String search,
            ProductType productType,
            PriceUseMode priceUseMode,
            DiscountType discountType,
            boolean offersOnly,
            UUID familyId,
            UUID taxId,
            Boolean offerActive,
            String stockStatus,
            UUID supplierId,
            UUID warehouseId,
            String sortBy,
            String sortDirection,
            UUID cursorId,
            int limit) {
        String expression = sortExpression(sortBy);
        String direction = sortDirection(sortDirection);
        String comparison = "asc".equals(direction) ? ">" : "<";
        var parameters = new MapSqlParameterSource()
                .addValue("storeId", storeId)
                .addValue("search", search)
                .addValue("productType", enumName(productType))
                .addValue("priceUseMode", enumName(priceUseMode))
                .addValue("discountType", enumName(discountType))
                .addValue("offersOnly", offersOnly)
                .addValue("familyId", familyId)
                .addValue("taxId", taxId)
                .addValue("offerActive", offerActive)
                .addValue("stockStatus", stockStatus)
                .addValue("supplierId", supplierId)
                .addValue("warehouseId", warehouseId)
                .addValue("cursorId", cursorId)
                .addValue("limit", limit);
        String sql = """
                with stock_rows as (
                    select product.id,
                           %s as sort_value
                    from producto product
                    left join producto_identificador code
                      on code.producto_id = product.id and code.tipo = 'CODIGO'
                    left join producto_identificador barcode
                      on barcode.producto_id = product.id and barcode.tipo = 'CODIGO_BARRAS'
                    left join familia family on family.id = product.familia_id
                    left join subfamilia subfamily on subfamily.id = product.subfamilia_id
                    left join impuesto_tienda tax on tax.id = product.impuesto_id
                    left join producto_precio sale_price
                      on sale_price.producto_id = product.id and sale_price.tarifa = 'VENTA'
                    left join producto_precio member_price
                      on member_price.producto_id = product.id and member_price.tarifa = 'MEMBER'
                    left join producto_precio wholesale_price
                      on wholesale_price.producto_id = product.id and wholesale_price.tarifa = 'MAYORISTA'
                    left join producto_precio offer_price
                      on offer_price.producto_id = product.id and offer_price.tarifa = 'OFERTA'
                    left join lateral (
                        select supplier.razon_social
                        from producto_proveedor product_supplier
                        join proveedor supplier on supplier.id = product_supplier.proveedor_id
                        where product_supplier.producto_id = product.id
                        order by product_supplier.ultimo_proveedor desc,
                                 product_supplier.principal desc,
                                 lower(supplier.razon_social),
                                 product_supplier.id
                        limit 1
                    ) supplier on true
                    left join lateral (
                        select coalesce(sum(stock.cantidad), 0) as total_stock,
                               coalesce(sum(stock.cantidad) filter (
                                   where cast(:warehouseId as uuid) is null
                                      or stock.almacen_id = cast(:warehouseId as uuid)
                               ), 0) as local_stock
                        from existencia stock
                        where stock.producto_id = product.id
                    ) stock on true
                    where product.tienda_id = :storeId
                      and (cast(:search as text) is null
                        or lower(product.nombre) like :search
                        or lower(coalesce(product.descripcion, '')) like :search
                        or lower(coalesce(product.comments, '')) like :search
                        or exists (
                          select identifier.id
                          from producto_identificador identifier
                          where identifier.producto_id = product.id
                            and lower(identifier.valor) like :search
                        ))
                      and (cast(:productType as varchar) is null
                        or product.product_type = cast(:productType as varchar))
                      and (cast(:priceUseMode as varchar) is null
                        or product.price_use_mode = cast(:priceUseMode as varchar))
                      and (cast(:discountType as varchar) is null
                        or product.discount_type = cast(:discountType as varchar))
                      and (:offersOnly = false
                        or product.price_use_mode in ('OFFER_PRICE', 'OFFER_DISCOUNT')
                        or product.discount_type = 'DISCOUNT_PRICE')
                      and (cast(:familyId as uuid) is null
                        or product.familia_id = cast(:familyId as uuid)
                        or product.subfamilia_id = cast(:familyId as uuid))
                      and (cast(:taxId as uuid) is null
                        or product.impuesto_id = cast(:taxId as uuid))
                      and (cast(:offerActive as boolean) is null
                        or product.oferta_activa = cast(:offerActive as boolean))
                      and (cast(:supplierId as uuid) is null or exists (
                        select 1
                        from producto_proveedor filtered_supplier
                        where filtered_supplier.producto_id = product.id
                          and filtered_supplier.proveedor_id = cast(:supplierId as uuid)
                      ))
                      and (cast(:stockStatus as varchar) is null
                        or (cast(:stockStatus as varchar) = 'INACTIVE' and not product.activo)
                        or (cast(:stockStatus as varchar) = 'EMPTY' and product.activo and stock.local_stock <= 0)
                        or (cast(:stockStatus as varchar) = 'LOW' and product.activo and stock.local_stock > 0 and stock.local_stock <= 5)
                        or (cast(:stockStatus as varchar) = 'OK' and product.activo and stock.local_stock > 5))
                ),
                cursor_row as (
                    select sort_value
                    from stock_rows
                    where id = cast(:cursorId as uuid)
                )
                select row.id
                from stock_rows row
                left join cursor_row cursor on true
                where cast(:cursorId as uuid) is null
                   or (
                       cursor.sort_value is not null
                       and (
                           row.sort_value is null
                           or row.sort_value %s cursor.sort_value
                           or (row.sort_value = cursor.sort_value and row.id %s cast(:cursorId as uuid))
                       )
                   )
                   or (
                       cursor.sort_value is null
                       and row.sort_value is null
                       and row.id %s cast(:cursorId as uuid)
                   )
                order by (row.sort_value is null), row.sort_value %s, row.id %s
                limit :limit
                """.formatted(expression, comparison, comparison, comparison, direction, direction);
        return jdbc.query(sql, parameters,
                (result, rowNumber) -> result.getObject("id", UUID.class));
    }

    static String sortExpression(String sortBy) {
        return switch (sortBy) {
            case "code" -> "lower(code.valor)";
            case "barcode" -> "lower(barcode.valor)";
            case "name" -> "lower(product.nombre)";
            case "type" -> "product.product_type";
            case "discount" -> "product.price_use_mode";
            case "supplier" -> "lower(supplier.razon_social)";
            case "family" -> "lower(family.nombre)";
            case "subfamily" -> "lower(subfamily.nombre)";
            case "tax" -> "tax.porcentaje";
            case "taxIncluded" -> "product.impuestos_incluidos";
            case "packageQuantity" -> "product.package_quantity";
            case "purchasePrice" -> "product.precio_compra";
            case "salePrice" -> "sale_price.importe";
            case "memberPrice" -> "member_price.importe";
            case "wholesalePrice" -> "wholesale_price.importe";
            case "offerPrice" -> "offer_price.importe";
            case "offerActive" -> "product.oferta_activa";
            case "offerFrom" -> "product.oferta_desde";
            case "offerUntil" -> "product.oferta_hasta";
            case "localStock" -> "stock.local_stock";
            case "totalStock" -> "stock.total_stock";
            case "stockMin" -> "product.stock_min";
            case "stockMax" -> "product.stock_max";
            case "status" -> "case when not product.activo then 0 when stock.local_stock <= 0 then 1 when stock.local_stock <= 5 then 2 else 3 end";
            default -> throw new IllegalArgumentException("Columna de ordenacion de stock no valida");
        };
    }

    private static String sortDirection(String value) {
        return switch (value) {
            case "asc" -> "asc";
            case "desc" -> "desc";
            default -> throw new IllegalArgumentException("Direccion de ordenacion no valida");
        };
    }

    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
