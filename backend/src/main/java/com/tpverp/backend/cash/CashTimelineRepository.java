package com.tpverp.backend.cash;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CashTimelineRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public CashTimelineRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }

    public List<CashTimelineView.Item> find(UUID storeId, UUID terminalId, Instant from, Instant to) {
        return jdbc.query("""
                with boundary as (
                    select coalesce(max(at),'-infinity'::timestamptz) as start_at from (
                        select max(abierta_en) as at from sesion_caja
                        where tienda_id=:storeId and terminal_id=:terminalId and abierta_en<:from
                        union all
                        select max(cerrada_en) from sesion_caja
                        where tienda_id=:storeId and terminal_id=:terminalId and estado='CERRADA' and cerrada_en<:from
                    ) anchors
                ), events as (
                    select 'opening:'||s.id as id, s.abierta_en as occurred_at, s.usuario_apertura_id as user_id,
                           'OPENING' as action, 'Apertura' as concept, s.fondo_inicial as amount,
                           s.fondo_inicial as reset_balance, s.id::text as source_reference, s.id as session_id, 0 as priority,
                           'ABIERTA'::text as cash_state
                    from sesion_caja s where s.tienda_id=:storeId and s.terminal_id=:terminalId and s.abierta_en<:to
                      and s.abierta_en>=(select start_at from boundary)
                    union all
                    select m.id::text, m.creado_en, m.usuario_id, m.tipo, coalesce(m.comentario,''),
                           case when m.tipo in ('RETIRADA','RETIRADA_CIERRE','RETIRADA_ENTRE_SESIONES','DEVOLUCION_EFECTIVO')
                                then -m.importe else m.importe end,
                           null::numeric, coalesce(d.numero,m.documento_id::text,m.id::text),m.sesion_caja_id,1,
                           case when m.sesion_caja_id is null then 'CERRADA' else 'ABIERTA' end
                    from movimiento_caja m left join documento d on d.id=m.documento_id and d.tienda_id=m.tienda_id
                    where m.tienda_id=:storeId and m.terminal_id=:terminalId and m.creado_en<:to
                      and m.creado_en>=(select start_at from boundary)
                    union all
                    select 'closing:'||s.id,s.cerrada_en,s.usuario_cierre_id,'CLOSING','Cierre',0::numeric,
                           s.fondo_dejado,s.id::text,s.id,2,'CERRADA'
                    from sesion_caja s where s.tienda_id=:storeId and s.terminal_id=:terminalId
                      and s.estado='CERRADA' and s.cerrada_en<:to
                      and s.cerrada_en>=(select start_at from boundary)
                ), groups as (
                    select *,count(reset_balance) over(order by occurred_at,priority,id rows unbounded preceding) as balance_group
                    from events
                ), balances as (
                    select *,coalesce(max(reset_balance) over(partition by balance_group),0)
                           +sum(case when reset_balance is null then amount else 0 end)
                            over(partition by balance_group order by occurred_at,priority,id rows unbounded preceding) as balance
                    from groups
                )
                select b.*,coalesce(u.user_name,'') username,coalesce(u.nombre,'') user_name,r.referencia as reference
                from balances b left join usuario u on u.id=b.user_id
                left join caja_actividad_referencia r on r.tienda_id=:storeId
                    and r.tipo_evento=case when b.action in ('OPENING','CLOSING') then b.action else 'MOVEMENT' end
                    and r.evento_id=case when b.action in ('OPENING','CLOSING') then b.session_id else b.id::uuid end
                where b.occurred_at>=:from and b.occurred_at<:to and b.action<>'COBRO_EFECTIVO'
                order by b.occurred_at,b.priority,b.id
                """, new MapSqlParameterSource("storeId",storeId).addValue("terminalId",terminalId)
                .addValue("from",Timestamp.from(from)).addValue("to",Timestamp.from(to)),
                (r,n)->new CashTimelineView.Item(r.getString("id"),r.getTimestamp("occurred_at").toInstant(),
                        r.getObject("user_id",UUID.class),r.getString("username"),r.getString("user_name"),
                        r.getString("action"),r.getString("concept"),r.getBigDecimal("amount"),
                        r.getBigDecimal("balance"),r.getString("reference"),r.getObject("session_id",UUID.class),
                        CashSessionStatus.valueOf(r.getString("cash_state")),r.getString("source_reference")));
    }
}
