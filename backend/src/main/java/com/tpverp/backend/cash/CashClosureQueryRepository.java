package com.tpverp.backend.cash;

import java.sql.Timestamp;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CashClosureQueryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public CashClosureQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT = """
                select session.id as session_id,
                       session.terminal_id,
                       terminal.nombre as terminal_name,
                       lower(terminal.nombre) as terminal_sort_key,
                       session.usuario_cierre_id as closing_user_id,
                       coalesce(account.nombre, '') as closing_user_name,
                       coalesce(account.user_name, '') as closing_username,
                       session.cerrada_en,
                       session.efectivo_teorico,
                       session.fondo_dejado,
                       session.descuadre,
                       session.cierre_tardio,
                       coalesce((select sum(m.importe) from movimiento_caja m
                         where m.sesion_caja_id=session.id and m.tipo='RETIRADA_CIERRE'),0) final_withdrawal,
                       session.fondo_dejado_desglose retained_breakdown,
                       (select jsonb_agg(jsonb_build_object('denomination',d.denominacion,'quantity',d.cantidad)
                                order by d.denominacion desc)
                        from movimiento_caja m join movimiento_caja_denominacion d on d.movimiento_caja_id=m.id
                        where m.sesion_caja_id=session.id and m.tipo='RETIRADA_CIERRE') withdrawal_breakdown
                from sesion_caja session
                join terminal terminal
                  on terminal.id = session.terminal_id
                 and terminal.tienda_id = session.tienda_id
                left join usuario account on account.id = session.usuario_cierre_id
                where session.tienda_id = :storeId
                  and session.estado = 'CERRADA'
                """;

    public List<CashClosureRow> findClosures(
            UUID storeId,
            Instant from,
            Instant toExclusive,
            UUID terminalId,
            UUID closingUserId,
            boolean onlyDiscrepancies,
            CashClosureCursor cursor,
            int limit) {
        var sql = new StringBuilder(SELECT + " and session.cerrada_en >= :from and session.cerrada_en < :toExclusive ");
        var parameters = new MapSqlParameterSource()
                .addValue("storeId", storeId)
                .addValue("from", Timestamp.from(from))
                .addValue("toExclusive", Timestamp.from(toExclusive))
                .addValue("limit", limit);
        if (terminalId != null) {
            sql.append(" and session.terminal_id = :terminalId\n");
            parameters.addValue("terminalId", terminalId);
        }
        if (closingUserId != null) {
            sql.append(" and session.usuario_cierre_id = :closingUserId\n");
            parameters.addValue("closingUserId", closingUserId);
        }
        if (onlyDiscrepancies) {
            sql.append(" and session.descuadre <> 0\n");
        }
        if (cursor != null) {
            sql.append("""
                     and (
                          lower(terminal.nombre) > :cursorTerminal
                          or (lower(terminal.nombre) = :cursorTerminal
                              and session.cerrada_en < :cursorClosedAt)
                          or (lower(terminal.nombre) = :cursorTerminal
                              and session.cerrada_en = :cursorClosedAt
                              and session.id > :cursorId)
                     )
                    """);
            parameters
                    .addValue("cursorTerminal", cursor.terminalSortKey())
                    .addValue("cursorClosedAt", Timestamp.from(cursor.closedAt()))
                    .addValue("cursorId", cursor.id());
        }
        sql.append("""
                order by lower(terminal.nombre) asc,
                         session.cerrada_en desc,
                         session.id asc
                limit :limit
                """);
        return jdbc.query(sql.toString(), parameters, (result, rowNumber) -> map(result));
    }

    public List<CashClosureRow> findClosures(
            UUID storeId,
            Instant from,
            Instant toExclusive,
            UUID terminalId,
            UUID closingUserId,
            boolean onlyDiscrepancies,
            CashClosureSortCursor cursor,
            int limit,
            String sortBy,
            String sortDirection) {
        var sort = closureSort(sortBy, sortDirection);
        var sql = new StringBuilder(SELECT + " and session.cerrada_en >= :from and session.cerrada_en < :toExclusive ");
        var parameters = new MapSqlParameterSource()
                .addValue("storeId", storeId)
                .addValue("from", Timestamp.from(from))
                .addValue("toExclusive", Timestamp.from(toExclusive))
                .addValue("limit", limit);
        if (terminalId != null) {
            sql.append(" and session.terminal_id = :terminalId\n");
            parameters.addValue("terminalId", terminalId);
        }
        if (closingUserId != null) {
            sql.append(" and session.usuario_cierre_id = :closingUserId\n");
            parameters.addValue("closingUserId", closingUserId);
        }
        if (onlyDiscrepancies) {
            sql.append(" and session.descuadre <> 0\n");
        }
        if (cursor != null) {
            sql.append(" and ((")
                    .append(sort.expression()).append(") ").append(sort.comparator()).append(" :cursorValue")
                    .append(" or ((").append(sort.expression()).append(") = :cursorValue and session.id > :cursorId))\n");
            parameters
                    .addValue("cursorValue", cursorValue(sort.type(), cursor.value()))
                    .addValue("cursorId", cursor.id());
        }
        sql.append(" order by ").append(sort.expression()).append(" ").append(sort.direction())
                .append(", session.id asc limit :limit");
        return jdbc.query(sql.toString(), parameters, (result, rowNumber) -> map(result));
    }

    public java.util.Optional<CashClosureRow> findById(UUID storeId, UUID sessionId) {
        return jdbc.query(SELECT + " and session.id=:id",new MapSqlParameterSource("storeId",storeId).addValue("id",sessionId),
                (result,rowNumber)->map(result)).stream().findFirst();
    }

    private static CashClosureRow map(java.sql.ResultSet result) throws java.sql.SQLException {
        return new CashClosureRow(
                result.getObject("session_id", UUID.class),
                result.getObject("terminal_id", UUID.class),
                result.getString("terminal_name"),
                result.getString("terminal_sort_key"),
                result.getObject("closing_user_id", UUID.class),
                result.getString("closing_user_name"),
                result.getString("closing_username"),
                result.getTimestamp("cerrada_en").toInstant(),
                result.getBigDecimal("efectivo_teorico"),
                result.getBigDecimal("fondo_dejado"),
                result.getBigDecimal("descuadre"),
                result.getBoolean("cierre_tardio"),result.getBigDecimal("final_withdrawal"),
                denominations(result.getString("retained_breakdown")),denominations(result.getString("withdrawal_breakdown")));
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    private static List<CashDenominationCommand> denominations(String value) {
        if(value==null) return List.of();
        try {
            return List.of(JSON.readValue(value,CashDenominationCommand[].class));
        } catch(com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("Desglose de caja persistido no valido",error);
        }
    }

    public List<CashClosureFilterOptionView> findTerminalOptions(UUID storeId) {
        return jdbc.query("""
                        select distinct terminal.id, terminal.nombre
                        from terminal terminal
                        join sesion_caja session
                          on session.terminal_id = terminal.id
                         and session.tienda_id = terminal.tienda_id
                        where session.tienda_id = :storeId
                        order by terminal.nombre
                        """,
                new MapSqlParameterSource("storeId", storeId),
                (result, rowNumber) -> new CashClosureFilterOptionView(
                        result.getObject("id", UUID.class),
                        result.getString("nombre"),
                        ""));
    }

    public List<CashClosureFilterOptionView> findUserOptions(UUID storeId) {
        return jdbc.query("""
                        select distinct account.id, account.nombre, account.user_name
                        from usuario account
                        join sesion_caja session on session.usuario_cierre_id = account.id or session.usuario_apertura_id = account.id
                        where session.tienda_id = :storeId
                        order by account.nombre, account.user_name
                        """,
                new MapSqlParameterSource("storeId", storeId),
                (result, rowNumber) -> new CashClosureFilterOptionView(
                        result.getObject("id", UUID.class),
                        result.getString("nombre"),
                        result.getString("user_name")));
    }

    record CashClosureCursor(String terminalSortKey, Instant closedAt, UUID id) {
    }

    record CashClosureSortCursor(String value, UUID id) {
    }

    private enum SortValueType { TEXT, INSTANT, DECIMAL }

    private record ClosureSort(String expression, String direction, String comparator, SortValueType type) {
    }

    private static ClosureSort closureSort(String sortBy, String sortDirection) {
        var expression = switch (sortBy) {
            case "terminal" -> "lower(terminal.nombre)";
            case "date", "time" -> "session.cerrada_en";
            case "user" -> "lower(coalesce(account.nombre, ''))";
            case "expectedCash" -> "session.efectivo_teorico";
            case "retainedFund" -> "session.fondo_dejado";
            case "discrepancy" -> "session.descuadre";
            default -> throw new IllegalArgumentException("Columna de ordenacion de cierres de caja no valida");
        };
        var type = switch (sortBy) {
            case "terminal", "user" -> SortValueType.TEXT;
            case "date", "time" -> SortValueType.INSTANT;
            default -> SortValueType.DECIMAL;
        };
        var direction = "desc".equals(sortDirection) ? "desc" : "asc";
        return new ClosureSort(expression, direction, direction.equals("asc") ? ">" : "<", type);
    }

    private static Object cursorValue(SortValueType type, String value) {
        return switch (type) {
            case TEXT -> value;
            case INSTANT -> Timestamp.from(Instant.ofEpochMilli(Long.parseLong(value)));
            case DECIMAL -> new BigDecimal(value);
        };
    }

    record CashClosureRow(
            UUID id,
            UUID terminalId,
            String terminalName,
            String terminalSortKey,
            UUID closingUserId,
            String closingUserName,
            String closingUsername,
            Instant closedAt,
            java.math.BigDecimal expectedCash,
            java.math.BigDecimal retainedFund,
            java.math.BigDecimal discrepancy,
            boolean lateClosing, BigDecimal finalWithdrawalAmount,
            List<CashDenominationCommand> retainedFundDenominations,
            List<CashDenominationCommand> finalWithdrawalDenominations) {
        CashClosureRow(UUID id,UUID terminalId,String terminalName,String terminalSortKey,
                UUID closingUserId,String closingUserName,String closingUsername,Instant closedAt,
                BigDecimal expectedCash,BigDecimal retainedFund,BigDecimal discrepancy,boolean lateClosing) {
            this(id,terminalId,terminalName,terminalSortKey,closingUserId,closingUserName,closingUsername,
                    closedAt,expectedCash,retainedFund,discrepancy,lateClosing,BigDecimal.ZERO,List.of(),List.of());
        }
    }
}
