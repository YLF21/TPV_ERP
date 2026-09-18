package com.tpverp.backend.ui;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Aggregate reads of the same logical sales activity used by the sales reports. */
@Repository
public class GestionSalesOverviewRepository {

    private static final String LOGICAL_DOCUMENTS = """
            select d.id, d.fecha, d.tipo, d.total
            from documento d
            join tienda store on store.id = d.tienda_id
            where d.tienda_id = :storeId and store.empresa_id = :companyId
              and d.fecha between :from and :to
              and d.tipo in ('TICKET', 'FACTURA_VENTA', 'RECTIFICATIVA_VENTA')
              and d.estado not in ('BORRADOR', 'ANULADO')
              %s
              and (d.tipo <> 'FACTURA_VENTA' or not exists (
                  select 1 from documento_relacion relation
                  join documento origin on origin.id = relation.origen_id
                  where relation.documento_id = d.id
                    and relation.tipo = 'FACTURA_DE' and origin.tipo = 'TICKET'
              ))
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public GestionSalesOverviewRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<DayAggregate> daily(
            UUID companyId, UUID storeId, UUID warehouseId, LocalDate from, LocalDate to) {
        return jdbc.query("with logical_documents as (" + logicalDocuments(warehouseId) + """
                )
                select fecha, coalesce(sum(total), 0) as net_sales,
                       count(*) as document_count
                from logical_documents
                group by fecha order by fecha
                """, parameters(companyId, storeId, warehouseId, from, to),
                (rs, row) -> new DayAggregate(
                        rs.getObject("fecha", LocalDate.class), rs.getBigDecimal("net_sales"),
                        rs.getLong("document_count")));
    }

    public List<TopProduct> topProducts(
            UUID companyId, UUID storeId, UUID warehouseId, LocalDate from, LocalDate to) {
        return jdbc.query("with logical_documents as (" + logicalDocuments(warehouseId) + """
                ), ranked as (
                    select line.producto_id, sum(line.cantidad) as net_quantity,
                           max(line.codigo) as historical_code,
                           max(line.nombre) as historical_name
                    from logical_documents d
                    join documento_linea line on line.documento_id = d.id
                    where line.tipo_linea = 'PRODUCT' and line.producto_id is not null
                      -- Economic correction quantities do not represent goods; preserve legacy rows without metadata.
                      and (d.tipo <> 'RECTIFICATIVA_VENTA' or not exists (
                          select 1 from factura_rectificacion_venta correction
                          where correction.documento_id = d.id and not correction.afecta_stock
                      ))
                    group by line.producto_id
                    having sum(line.cantidad) > 0
                    order by net_quantity desc, line.producto_id
                    limit 10
                )
                select ranked.producto_id, ranked.net_quantity,
                       coalesce(identifier.valor, ranked.historical_code) as code,
                       coalesce(product.nombre, ranked.historical_name) as name
                from ranked
                left join producto product on product.id = ranked.producto_id
                    and product.tienda_id = :storeId
                left join producto_identificador identifier on identifier.producto_id = product.id
                    and identifier.tienda_id = :storeId and identifier.tipo = 'CODIGO'
                order by ranked.net_quantity desc, ranked.producto_id
                """, parameters(companyId, storeId, warehouseId, from, to),
                (rs, row) -> new TopProduct(
                        rs.getObject("producto_id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getBigDecimal("net_quantity")));
    }

    private static String logicalDocuments(UUID warehouseId) {
        return LOGICAL_DOCUMENTS.formatted(warehouseId == null ? "" : "and d.almacen_id = :warehouseId");
    }

    private static MapSqlParameterSource parameters(
            UUID companyId, UUID storeId, UUID warehouseId, LocalDate from, LocalDate to) {
        return new MapSqlParameterSource()
                .addValue("companyId", companyId).addValue("storeId", storeId)
                .addValue("warehouseId", warehouseId)
                .addValue("from", java.sql.Date.valueOf(from)).addValue("to", java.sql.Date.valueOf(to));
    }

    public record DayAggregate(LocalDate date, BigDecimal netSales, long documentCount) {}

    public record TopProduct(UUID productId, String code, String name, BigDecimal netQuantity) {}
}
