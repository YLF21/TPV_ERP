package com.tpverp.backend.cash;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CashActivityRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public CashActivityRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Row> find(UUID storeId, Instant from, Instant to, UUID terminalId, UUID userId,
            String action, String cashState, String sortBy, String direction, Cursor cursor, int limit) {
        var sort = sortExpression(sortBy);
        if (!direction.equals("asc") && !direction.equals("desc")) {
            throw new IllegalArgumentException("Direccion de ordenacion no valida");
        }
        var parameters = new MapSqlParameterSource("storeId", storeId)
                .addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to)).addValue("limit", limit);
        var terminals = "";
        if (terminalId != null) {
            terminals = " and t.id=:terminalId";
            parameters.addValue("terminalId", terminalId);
        }
        var filters = new StringBuilder();
        if (userId != null) {
            filters.append(" and b.user_id=:userId");
            parameters.addValue("userId", userId);
        }
        if (action != null) {
            filters.append(" and b.action=:action");
            parameters.addValue("action", action);
        }
        if (cashState != null) {
            filters.append(" and b.cash_state=:cashState");
            parameters.addValue("cashState", cashState);
        }
        var continuation = "";
        if (cursor != null) {
            continuation = " where (sort_value,occurred_at,priority,id) " + (direction.equals("asc") ? ">" : "<")
                    + " (:cursorValue,:cursorAt,:cursorPriority,:cursorId)";
            parameters.addValue("cursorValue", cursorValue(sortBy, cursor.value())).addValue("cursorId", cursor.id())
                    .addValue("cursorAt", Timestamp.from(cursor.occurredAt())).addValue("cursorPriority", cursor.priority());
        }
        // Each terminal starts at its own last reset. User/action/state filters run after the full balance,
        // so omitted cash receipts and movements by other users still contribute to the balance.
        var sql = """
                with boundaries as (
                    select t.id as terminal_id,t.nombre as terminal_name,
                           greatest(coalesce((select max(s.abierta_en) from sesion_caja s
                               where s.tienda_id=:storeId and s.terminal_id=t.id and s.abierta_en<:from),'-infinity'),
                               coalesce((select max(s.cerrada_en) from sesion_caja s
                               where s.tienda_id=:storeId and s.terminal_id=t.id and s.estado='CERRADA'
                                 and s.cerrada_en<:from),'-infinity')) as start_at
                    from terminal t where t.tienda_id=:storeId
                """ + terminals + """
                ), events as (
                    select 'opening:'||s.id as id,s.abierta_en as occurred_at,s.usuario_apertura_id as user_id,
                           'OPENING' as action,'Apertura' as concept,s.fondo_inicial as amount,
                           s.fondo_inicial as reset_balance,s.id::text as source_reference,s.id as session_id,
                           0 as priority,'ABIERTA'::text as cash_state,t.terminal_id,t.terminal_name
                    from boundaries t join sesion_caja s on s.terminal_id=t.terminal_id and s.tienda_id=:storeId
                    where s.abierta_en>=t.start_at and s.abierta_en<:to
                    union all
                    select m.id::text,m.creado_en,m.usuario_id,m.tipo,coalesce(m.comentario,''),
                           case when m.tipo in ('RETIRADA','RETIRADA_CIERRE','RETIRADA_ENTRE_SESIONES','DEVOLUCION_EFECTIVO')
                                then -m.importe else m.importe end,
                           null::numeric,coalesce(d.numero,m.documento_id::text,m.id::text),m.sesion_caja_id,
                           1,case when m.sesion_caja_id is null then 'CERRADA' else 'ABIERTA' end,t.terminal_id,t.terminal_name
                    from boundaries t join movimiento_caja m on m.terminal_id=t.terminal_id and m.tienda_id=:storeId
                    left join documento d on d.id=m.documento_id and d.tienda_id=m.tienda_id
                    where m.creado_en>=t.start_at and m.creado_en<:to
                    union all
                    select 'closing:'||s.id,s.cerrada_en,s.usuario_cierre_id,'CLOSING','Cierre',0::numeric,
                           s.fondo_dejado,s.id::text,s.id,2,'CERRADA',t.terminal_id,t.terminal_name
                    from boundaries t join sesion_caja s on s.terminal_id=t.terminal_id and s.tienda_id=:storeId
                    where s.estado='CERRADA' and s.cerrada_en>=t.start_at and s.cerrada_en<:to
                ), balance_groups as (
                    select *,count(reset_balance) over(partition by terminal_id
                        order by occurred_at,priority,id rows unbounded preceding) as balance_group from events
                ), balances as (
                    select *,coalesce(max(reset_balance) over(partition by terminal_id,balance_group),0)
                        +sum(case when reset_balance is null then amount else 0 end) over(
                            partition by terminal_id,balance_group order by occurred_at,priority,id rows unbounded preceding) as balance
                    from balance_groups
                ), visible as (
                    select b.*,coalesce(u.user_name,'') as username,coalesce(u.nombre,'') as user_name,r.referencia as reference
                    from balances b left join usuario u on u.id=b.user_id
                    left join caja_actividad_referencia r on r.tienda_id=:storeId
                        and r.tipo_evento=case when b.action in ('OPENING','CLOSING') then b.action else 'MOVEMENT' end
                        and r.evento_id=case when b.action in ('OPENING','CLOSING') then b.session_id else b.id::uuid end
                    where b.occurred_at>=:from and b.occurred_at<:to and b.action<>'COBRO_EFECTIVO'
                """ + filters + "), sorted as (select *," + sort + " as sort_value from visible) select * from sorted"
                + continuation + " order by sort_value " + direction + ",occurred_at " + direction
                + ",priority " + direction + ",id " + direction + " limit :limit";
        return jdbc.query(sql, parameters, (r, n) -> {
            var item = new CashActivityView(r.getString("id"), r.getTimestamp("occurred_at").toInstant(),
                    r.getObject("user_id", UUID.class), r.getString("username"), r.getString("user_name"),
                    r.getString("action"), r.getString("concept"), r.getBigDecimal("amount"), r.getBigDecimal("balance"),
                    r.getString("reference"), r.getObject("session_id", UUID.class),
                    CashSessionStatus.valueOf(r.getString("cash_state")), r.getString("source_reference"),
                    r.getObject("terminal_id", UUID.class), null, r.getString("terminal_name"));
            var value = switch (sortBy) {
                case "date", "time" -> r.getTimestamp("sort_value").toInstant().toString();
                default -> r.getString("sort_value");
            };
            return new Row(item, value, r.getInt("priority"));
        });
    }

    static String sortExpression(String sortBy) {
        return switch (sortBy) {
            case "reference" -> "coalesce(reference,'')";
            case "terminal" -> "lower(terminal_name)";
            case "date", "time" -> "occurred_at";
            case "user" -> "lower(user_name)";
            case "action" -> "action";
            case "cashState" -> "cash_state";
            case "concept" -> "lower(concept)";
            case "quantity" -> "amount";
            case "balance" -> "balance";
            default -> throw new IllegalArgumentException("Columna de ordenacion de actividad de caja no valida");
        };
    }

    static Object cursorValue(String sortBy, String value) {
        return switch (sortBy) {
            case "date", "time" -> Timestamp.from(Instant.parse(value));
            case "quantity", "balance" -> new BigDecimal(value);
            default -> value;
        };
    }

    public LocalDate earliestDate(UUID storeId, String timezone) {
        return jdbc.queryForObject("""
                select (min(at) at time zone :timezone)::date from (
                    select min(abierta_en) as at from sesion_caja where tienda_id=:storeId
                    union all
                    select min(creado_en) from movimiento_caja where tienda_id=:storeId and tipo<>'COBRO_EFECTIVO'
                ) dates
                """, new MapSqlParameterSource("storeId", storeId).addValue("timezone", timezone), LocalDate.class);
    }

    public List<CashClosureFilterOptionView> terminalOptions(UUID storeId) {
        return jdbc.query("select id,nombre from terminal where tienda_id=:storeId order by lower(nombre),id",
                new MapSqlParameterSource("storeId", storeId), (r, n) -> new CashClosureFilterOptionView(
                        r.getObject("id", UUID.class), r.getString("nombre"), ""));
    }

    public List<CashClosureFilterOptionView> userOptions(UUID storeId) {
        return jdbc.query("select u.id,u.nombre,u.user_name from usuario u where " + USER_IN_STORE
                        + " order by lower(u.nombre),u.id",
                new MapSqlParameterSource("storeId", storeId), (r, n) -> new CashClosureFilterOptionView(
                        r.getObject("id", UUID.class), r.getString("nombre"), r.getString("user_name")));
    }

    // Global administrators and users whose store assignment changed still own their historical events.
    private static final String USER_IN_STORE = """
            (u.tienda_id=:storeId
             or exists(select 1 from sesion_caja s where s.tienda_id=:storeId
                 and (s.usuario_apertura_id=u.id or s.usuario_cierre_id=u.id))
             or exists(select 1 from movimiento_caja m where m.tienda_id=:storeId and m.usuario_id=u.id))
            """;

    public boolean userHasStoreActivity(UUID storeId, UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from usuario u where u.id=:userId and "
                + USER_IN_STORE + ")", new MapSqlParameterSource("storeId", storeId).addValue("userId", userId), Boolean.class));
    }

    public record Cursor(String value, Instant occurredAt, int priority, String id) { }
    public record Row(CashActivityView item, String sortValue, int priority) { }
}
