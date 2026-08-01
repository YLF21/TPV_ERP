package com.tpverp.backend.cash;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CashCurrentBalanceQueryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public CashCurrentBalanceQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<CashCurrentBalanceView> findCurrentBalances(UUID storeId) {
        var sql = """
                select terminal.id as terminal_id,
                       terminal.nombre as terminal_name,
                       case
                           when open_session.id is not null then 'ABIERTA'
                           when last_closed.id is not null then 'CERRADA'
                           else 'SIN_SESION'
                       end as cash_status,
                       open_session.usuario_apertura_id,
                       opening_user.nombre as opening_user_name,
                       opening_user.user_name as opening_username,
                       open_session.abierta_en,
                       case
                           when open_session.id is not null then
                               open_session.fondo_inicial + coalesce(open_movements.balance, 0)
                           else
                               coalesce(last_closed.fondo_dejado, 0) + coalesce(between_movements.balance, 0)
                       end as expected_cash,
                       case
                           when open_session.id is not null then
                               greatest(open_session.abierta_en,
                                        coalesce(open_movements.last_movement_at, open_session.abierta_en))
                           else coalesce(between_movements.last_movement_at, last_closed.cerrada_en)
                       end as last_activity_at
                from terminal
                left join lateral (
                    select session.id,
                           session.usuario_apertura_id,
                           session.abierta_en,
                           session.fondo_inicial
                    from sesion_caja session
                    where session.terminal_id = terminal.id
                      and session.tienda_id = terminal.tienda_id
                      and session.estado = 'ABIERTA'
                    limit 1
                ) open_session on true
                left join usuario opening_user on opening_user.id = open_session.usuario_apertura_id
                left join lateral (
                    select coalesce(sum(case movement.tipo
                               when 'COBRO_EFECTIVO' then movement.importe
                               when 'ENTRADA' then movement.importe
                               when 'DEVOLUCION_EFECTIVO' then -movement.importe
                               when 'RETIRADA' then -movement.importe
                               when 'RETIRADA_CIERRE' then -movement.importe
                               else 0
                           end), 0) as balance,
                           max(movement.creado_en) as last_movement_at
                    from movimiento_caja movement
                    where movement.sesion_caja_id = open_session.id
                ) open_movements on open_session.id is not null
                left join lateral (
                    select session.id,
                           session.cerrada_en,
                           session.fondo_dejado
                    from sesion_caja session
                    where session.terminal_id = terminal.id
                      and session.tienda_id = terminal.tienda_id
                      and session.estado = 'CERRADA'
                    order by session.cerrada_en desc, session.id
                    limit 1
                ) last_closed on true
                left join lateral (
                    select coalesce(sum(case movement.tipo
                               when 'ENTRADA_ENTRE_SESIONES' then movement.importe
                               when 'RETIRADA_ENTRE_SESIONES' then -movement.importe
                               else 0
                           end), 0) as balance,
                           max(movement.creado_en) as last_movement_at
                    from movimiento_caja movement
                    where movement.terminal_id = terminal.id
                      and movement.tienda_id = terminal.tienda_id
                      and movement.sesion_caja_id is null
                      and movement.creado_en >= coalesce(last_closed.cerrada_en, '-infinity'::timestamptz)
                ) between_movements on open_session.id is null
                where terminal.tienda_id = :storeId
                  and terminal.activa = true
                  and terminal.aprobada = true
                order by lower(terminal.nombre), terminal.id
                """;
        return jdbc.query(
                sql,
                new MapSqlParameterSource("storeId", storeId),
                (result, rowNumber) -> new CashCurrentBalanceView(
                        result.getObject("terminal_id", UUID.class),
                        result.getString("terminal_name"),
                        CashCurrentBalanceStatus.valueOf(result.getString("cash_status")),
                        result.getObject("usuario_apertura_id", UUID.class),
                        result.getString("opening_user_name"),
                        result.getString("opening_username"),
                        result.getTimestamp("abierta_en") == null
                                ? null
                                : result.getTimestamp("abierta_en").toInstant(),
                        result.getBigDecimal("expected_cash"),
                        result.getTimestamp("last_activity_at") == null
                                ? null
                                : result.getTimestamp("last_activity_at").toInstant()));
    }
}
