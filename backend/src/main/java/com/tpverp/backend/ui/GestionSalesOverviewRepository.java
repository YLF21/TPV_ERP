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
            select d.id, d.fecha, d.tipo, d.total, d.descuento_global,
                   coalesce(d.confirmado_en, d.creado_en) as recorded_at
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
        return topProducts(companyId, storeId, warehouseId, from, to, false);
    }

    public List<TopProduct> topProductsByAmount(
            UUID companyId, UUID storeId, UUID warehouseId, LocalDate from, LocalDate to) {
        return topProducts(companyId, storeId, warehouseId, from, to, true);
    }

    private List<TopProduct> topProducts(
            UUID companyId, UUID storeId, UUID warehouseId, LocalDate from, LocalDate to, boolean amount) {
        // Both rankings are computed over ALL products on the server, never by re-sorting a truncated top ten.
        var metric = amount ? "net_amount" : "net_quantity";
        return jdbc.query(allocatedLines(warehouseId) + """
                , ranked as (
                    select product_id, max(code) as code, max(name) as name,
                           sum(quantity) as net_quantity, sum(amount) as net_amount
                    from allocated_lines where product_id is not null
                    group by product_id
                )
                select ranked.*, family.id as family_id, family.nombre as family_name
                from ranked
                left join producto product on product.id = ranked.product_id and product.tienda_id = :storeId
                left join familia family on family.id = product.familia_id and family.tienda_id = :storeId
                """ + " where " + metric + " > 0 order by " + metric + " desc, product_id limit 10",
                parameters(companyId, storeId, warehouseId, from, to),
                (rs, row) -> new TopProduct(rs.getObject("product_id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getBigDecimal("net_quantity"), rs.getBigDecimal("net_amount"),
                        rs.getObject("family_id", UUID.class), rs.getString("family_name")));
    }

    public List<FamilySales> families(UUID companyId, UUID storeId, UUID warehouseId,
            LocalDate from, LocalDate to, LocalDate currentFrom) {
        return jdbc.query(allocatedLines(warehouseId) + """
                , family_lines as (
                    select d.fecha, coalesce(family.id::text, 'UNCLASSIFIED') as key,
                           family.nombre as name, line.quantity, line.amount
                    from allocated_lines line
                    join logical_documents d on d.id = line.document_id
                    left join producto product on product.id = line.product_id and product.tienda_id = :storeId
                    left join familia family on family.id = product.familia_id and family.tienda_id = :storeId
                    union all
                    -- Unattributable adjustments, documents without lines and rounding residuals reconcile to the document.
                    select d.fecha, 'ADJUSTMENTS', null, 0, d.total - coalesce(sum(line.amount), 0)
                    from logical_documents d left join allocated_lines line on line.document_id = d.id
                    group by d.id, d.fecha, d.total
                    having d.total <> coalesce(sum(line.amount), 0)
                )
                select key, max(name) as name,
                    coalesce(sum(amount) filter (where fecha >= :currentFrom), 0) as current_sales,
                    coalesce(sum(amount) filter (where fecha < :currentFrom), 0) as previous_sales,
                    coalesce(sum(quantity) filter (where fecha >= :currentFrom), 0) as current_units,
                    coalesce(sum(quantity) filter (where fecha < :currentFrom), 0) as previous_units
                from family_lines group by key
                order by current_sales desc, key
                """, parameters(companyId, storeId, warehouseId, from, to)
                        .addValue("currentFrom", java.sql.Date.valueOf(currentFrom)),
                (rs, row) -> new FamilySales(rs.getString("key"), rs.getString("name"),
                        rs.getBigDecimal("current_sales"), rs.getBigDecimal("previous_sales"),
                        rs.getBigDecimal("current_units"), rs.getBigDecimal("previous_units")));
    }

    private static String allocatedLines(UUID warehouseId) {
        return "with logical_documents as (" + logicalDocuments(warehouseId) + """
                ), allocated_lines as (
                    select d.id as document_id,
                           coalesce(line.producto_id, source.producto_id) as product_id,
                           coalesce(identifier.valor, source.codigo, line.codigo) as code,
                           coalesce(product.nombre, source.nombre, line.nombre) as name,
                           case when line.tipo_linea = 'PRODUCT' and not exists (
                               select 1 from factura_rectificacion_venta correction
                               where correction.documento_id = d.id and not correction.afecta_stock
                           ) then line.cantidad else 0 end as quantity,
                           round(line.total * (1 - d.descuento_global / 100), 2) as amount
                    from logical_documents d
                    join documento_linea line on line.documento_id = d.id
                    left join documento_linea source on source.id = line.linea_origen_id
                        and source.documento_id = d.id and source.tipo_linea = 'PRODUCT'
                    left join producto product on product.id = coalesce(line.producto_id, source.producto_id)
                        and product.tienda_id = :storeId
                    left join producto_identificador identifier on identifier.producto_id = product.id
                        and identifier.tienda_id = :storeId and identifier.tipo = 'CODIGO'
                    where line.tipo_linea = 'PRODUCT' or coalesce(line.producto_id, source.producto_id) is not null
                )
                """;
    }

    public List<HourSales> hourly(UUID companyId, UUID storeId, UUID warehouseId,
            LocalDate from, LocalDate to, String timezone) {
        return jdbc.query(allocatedLines(warehouseId) + """
                , quantities as (
                    select document_id, sum(quantity) as units from allocated_lines group by document_id
                ), hours as (
                    select d.fecha,
                        case when (d.recorded_at at time zone :timezone)::date = d.fecha
                            then extract(hour from d.recorded_at at time zone :timezone)::int else -1 end as hour,
                        d.total, coalesce(q.units, 0) as units
                    from logical_documents d left join quantities q on q.document_id = d.id
                )
                select fecha, hour, sum(total) as sales, sum(units) as units, count(*) as operations
                from hours group by fecha, hour order by fecha, hour
                """, parameters(companyId, storeId, warehouseId, from, to).addValue("timezone", timezone),
                (rs, row) -> new HourSales(rs.getObject("fecha", LocalDate.class), rs.getInt("hour"),
                        rs.getBigDecimal("sales"), rs.getBigDecimal("units"), rs.getLong("operations")));
    }

    public List<CorrectionSummary> corrections(UUID companyId, UUID storeId, UUID warehouseId,
            LocalDate from, LocalDate to) {
        return jdbc.query("with logical_documents as (" + logicalDocuments(warehouseId) + """
                ), classified as (
                    select d.total, case
                        when d.total > 0 then 'INCREASES'
                        when d.total = 0 then 'ZERO'
                        when coalesce(c.afecta_stock, true) then 'RETURNS'
                        else 'ECONOMIC' end as kind
                    from logical_documents d
                    left join factura_rectificacion_venta c on c.documento_id = d.id
                    where d.tipo = 'RECTIFICATIVA_VENTA' or d.total < 0
                )
                select kind, count(*) as operations, sum(total) as amount
                from classified group by kind order by kind
                """, parameters(companyId, storeId, warehouseId, from, to),
                (rs, row) -> new CorrectionSummary(rs.getString("kind"), rs.getLong("operations"), rs.getBigDecimal("amount")));
    }

    /** Actual settlement entries by their own timestamp, including collections of older debt. */
    public List<PaymentSummary> payments(UUID companyId, UUID storeId, UUID warehouseId,
            LocalDate from, LocalDate to, String timezone) {
        var zone = java.time.ZoneId.of(timezone);
        return jdbc.query("""
                with scoped_documents as (
                    select d.id from documento d join tienda s on s.id = d.tienda_id
                    where d.tienda_id = :storeId and s.empresa_id = :companyId
                        and d.estado not in ('BORRADOR', 'ANULADO')
                        and d.tipo in ('TICKET', 'FACTURA_VENTA', 'ALBARAN_VENTA', 'RECTIFICATIVA_VENTA')
                """ + (warehouseId == null ? "" : " and d.almacen_id = :warehouseId ") + """
                ), entries as (
                    select m.nombre as method, p.importe as collected, 0::numeric as refunded
                    from documento_pago p join scoped_documents d on d.id = p.documento_id
                    join metodo_pago m on m.id = p.metodo_pago_id and m.empresa_id = :companyId
                    where p.creado_en >= :start and p.creado_en < :end
                        and m.nombre <> 'COMPENSACION_DEVOLUCION'
                    union all
                    select case r.tipo when 'CASH' then 'EFECTIVO' when 'VOUCHER' then 'VALE'
                        when 'TRANSFER' then 'TRANSFERENCIA' when 'MEMBER_CREDIT' then 'SALDO_MIEMBRO'
                        when 'CARD' then case when r.terminal_operacion_id is null and m.nombre = 'TRANSFERENCIA'
                            then 'TRANSFERENCIA' else 'TARJETA' end end,
                        0, r.importe
                    from documento_devolucion_pago r join scoped_documents d on d.id = r.documento_devolucion_id
                    left join documento_pago p on p.id = r.documento_pago_original_id
                    left join metodo_pago m on m.id = p.metodo_pago_id and m.empresa_id = :companyId
                    where r.creado_en >= :start and r.creado_en < :end and r.tipo <> 'EXCHANGE'
                )
                select method, sum(collected) as collected, sum(refunded) as refunded,
                    sum(collected - refunded) as net from entries group by method order by collected desc, method
                """, parameters(companyId, storeId, warehouseId, from, to)
                        .addValue("start", from.atStartOfDay(zone).toOffsetDateTime())
                        .addValue("end", to.plusDays(1).atStartOfDay(zone).toOffsetDateTime()),
                (rs, row) -> new PaymentSummary(rs.getString("method"), rs.getBigDecimal("collected"),
                        rs.getBigDecimal("refunded"), rs.getBigDecimal("net")));
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

    public record TopProduct(UUID productId, String code, String name, BigDecimal netQuantity,
            BigDecimal netAmount, UUID familyId, String familyName) {
        public TopProduct(UUID productId, String code, String name, BigDecimal netQuantity) {
            this(productId, code, name, netQuantity, BigDecimal.ZERO, null, null);
        }
    }

    public record FamilySales(String key, String name, BigDecimal currentSales, BigDecimal previousSales,
            BigDecimal currentUnits, BigDecimal previousUnits) {}
    public record HourSales(LocalDate date, int hour, BigDecimal sales, BigDecimal units, long operations) {}
    public record CorrectionSummary(String kind, long operations, BigDecimal amount) {}
    public record PaymentSummary(String method, BigDecimal collected, BigDecimal refunded, BigDecimal net) {}
}
