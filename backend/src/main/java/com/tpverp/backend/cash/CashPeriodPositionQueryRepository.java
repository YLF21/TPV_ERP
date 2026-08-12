package com.tpverp.backend.cash;

import com.tpverp.backend.document.Money;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CashPeriodPositionQueryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public CashPeriodPositionQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns the theoretical cash held by every active terminal immediately
     * before the supplied boundary. This makes historical range reports use a
     * real opening position instead of summing session opening funds twice.
     */
    public BigDecimal positionAt(UUID storeId, Instant boundary) {
        var sql = """
                select coalesce(sum(
                    case
                        when active_session.id is not null then
                            active_session.fondo_inicial + coalesce(active_movements.balance, 0)
                        else
                            coalesce(last_closed.fondo_dejado, 0)
                                + coalesce(between_movements.balance, 0)
                    end
                ), 0) as expected_cash
                from terminal
                left join lateral (
                    select session.id, session.fondo_inicial
                    from sesion_caja session
                    where session.terminal_id = terminal.id
                      and session.tienda_id = terminal.tienda_id
                      and session.abierta_en < :boundary
                      and (session.cerrada_en is null or session.cerrada_en >= :boundary)
                    order by session.abierta_en desc, session.id
                    limit 1
                ) active_session on true
                left join lateral (
                    select coalesce(sum(case movement.tipo
                               when 'COBRO_EFECTIVO' then movement.importe
                               when 'ENTRADA' then movement.importe
                               when 'DEVOLUCION_EFECTIVO' then -movement.importe
                               when 'RETIRADA' then -movement.importe
                               when 'RETIRADA_CIERRE' then -movement.importe
                               else 0
                           end), 0) as balance
                    from movimiento_caja movement
                    where movement.sesion_caja_id = active_session.id
                      and movement.creado_en < :boundary
                ) active_movements on active_session.id is not null
                left join lateral (
                    select session.id, session.cerrada_en, session.fondo_dejado
                    from sesion_caja session
                    where session.terminal_id = terminal.id
                      and session.tienda_id = terminal.tienda_id
                      and session.cerrada_en < :boundary
                    order by session.cerrada_en desc, session.id
                    limit 1
                ) last_closed on active_session.id is null
                left join lateral (
                    select coalesce(sum(case movement.tipo
                               when 'ENTRADA_ENTRE_SESIONES' then movement.importe
                               when 'RETIRADA_ENTRE_SESIONES' then -movement.importe
                               else 0
                           end), 0) as balance
                    from movimiento_caja movement
                    where movement.terminal_id = terminal.id
                      and movement.tienda_id = terminal.tienda_id
                      and movement.sesion_caja_id is null
                      and movement.creado_en >= coalesce(last_closed.cerrada_en, '-infinity'::timestamptz)
                      and movement.creado_en < :boundary
                ) between_movements on active_session.id is null
                where terminal.tienda_id = :storeId
                  and terminal.activa = true
                  and terminal.aprobada = true
                """;
        var amount = jdbc.queryForObject(
                sql,
                new MapSqlParameterSource()
                        .addValue("storeId", storeId)
                        .addValue("boundary", Timestamp.from(boundary)),
                BigDecimal.class);
        return Money.euros(amount == null ? BigDecimal.ZERO : amount);
    }
}
