package com.tpverp.backend.document;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CustomerModel347Repository {

    private final NamedParameterJdbcTemplate jdbc;

    public CustomerModel347Repository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<CustomerModel347Report.Quarter> quarterTotals(UUID companyId, UUID customerId, int year) {
        if (companyId == null || customerId == null || year < 1 || year > 9998) {
            throw new IllegalArgumentException("Invalid annual customer report parameters");
        }
        // Aggregate persisted totals in SQL: no paging, payment/line joins or recalculation.
        // Both parties must belong to the authenticated company, including every store.
        return jdbc.query("""
                select extract(quarter from document.fecha)::integer as quarter_number,
                       sum(document.total) as total, count(*) as document_count
                from documento document
                join tienda store on store.id = document.tienda_id
                join cliente customer on customer.id = document.cliente_id
                where store.empresa_id = :companyId and customer.empresa_id = :companyId
                  and document.cliente_id = :customerId
                  and document.tipo in ('FACTURA_VENTA', 'RECTIFICATIVA_VENTA')
                  and document.estado in ('CONFIRMADO', 'PENDIENTE', 'PARCIAL', 'PAGADO')
                  and document.fecha >= :dateFrom and document.fecha < :dateUntil
                group by extract(quarter from document.fecha)
                order by quarter_number
                """, new MapSqlParameterSource()
                        .addValue("companyId", companyId).addValue("customerId", customerId)
                        .addValue("dateFrom", LocalDate.of(year, 1, 1))
                        .addValue("dateUntil", LocalDate.of(year + 1, 1, 1)),
                (row, index) -> new CustomerModel347Report.Quarter(
                        row.getInt("quarter_number"), row.getBigDecimal("total"), row.getLong("document_count")));
    }
}
