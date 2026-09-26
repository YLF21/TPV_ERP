package com.tpverp.backend.ui;

import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Current receivables, with the same eligibility and settlement rules as CustomerReceivableService. */
@RestController
@RequestMapping("/api/v1/gestion/dashboard/data")
public class GestionDashboardReceivablesController {
    private final NamedParameterJdbcTemplate jdbc;
    private final CurrentOrganization organization;
    private final WarehouseRepository warehouses;
    private final Clock clock;

    public GestionDashboardReceivablesController(NamedParameterJdbcTemplate jdbc, CurrentOrganization organization,
            WarehouseRepository warehouses, Clock clock) {
        this.jdbc = jdbc;
        this.organization = organization;
        this.warehouses = warehouses;
        this.clock = clock;
    }

    @GetMapping("/receivables-summary")
    @PreAuthorize("(hasRole('ADMIN') or hasAuthority('APP_GESTION_ACCESS'))"
            + " and (hasRole('ADMIN') or hasAuthority('CUSTOMER_RECEIVABLES_READ'))")
    @Transactional(readOnly = true)
    public Summary summary(@RequestParam(required = false) UUID warehouseId) {
        var store = organization.currentStore();
        if (warehouseId != null && warehouses.findByStoreIdAndIdIn(store.getId(), List.of(warehouseId)).isEmpty()) {
            throw new IllegalArgumentException("message.warehouse.not_found");
        }
        var today = LocalDate.now(clock.withZone(ZoneId.of(store.getTimezone())));
        var rows = jdbc.query("""
                with balances as (
                    select d.fecha_vencimiento, case when d.liquidado_por_origen then 0
                        else d.total - coalesce((select sum(p.importe) from documento_pago p where p.documento_id = d.id), 0)
                        end as pending
                    from documento d join tienda s on s.id = d.tienda_id
                    where d.tienda_id = :storeId and s.empresa_id = :companyId
                        and d.tipo in ('ALBARAN_VENTA', 'FACTURA_VENTA', 'TICKET')
                        and (d.tipo <> 'TICKET' or d.cuenta_cobrar)
                        and d.estado in ('PENDIENTE', 'PARCIAL') and d.cliente_id is not null
                """ + (warehouseId == null ? "" : " and d.almacen_id = :warehouseId ") + """
                        and not exists (select 1 from documento_relacion r join documento target on target.id = r.documento_id
                            where r.origen_id = d.id and r.tipo = 'FACTURA_DE'
                                and target.estado not in ('BORRADOR', 'ANULADO'))
                ), buckets as (
                    select case when fecha_vencimiento is null then 'NO_DUE_DATE'
                        when fecha_vencimiento < :today then 'OVERDUE' else 'NOT_DUE' end as kind, pending
                    from balances where pending > 0
                )
                select kind, count(*) as documents, sum(pending) as amount from buckets group by kind order by kind
                """, new MapSqlParameterSource().addValue("storeId", store.getId())
                        .addValue("companyId", store.getEmpresa().getId()).addValue("warehouseId", warehouseId)
                        .addValue("today", java.sql.Date.valueOf(today)),
                (rs, row) -> new Balance(rs.getString("kind"), rs.getLong("documents"), rs.getBigDecimal("amount")));
        return new Summary(today, "EUR", rows);
    }

    public record Summary(LocalDate asOf, String currency, List<Balance> balances) {}
    public record Balance(String kind, long documents, BigDecimal amount) {}
}
