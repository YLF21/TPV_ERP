package com.tpverp.backend.cash;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CashAlertRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final CashOpeningAlertRepository openings;

    public CashAlertRepository(NamedParameterJdbcTemplate jdbc, CashOpeningAlertRepository openings) {
        this.jdbc = jdbc;
        this.openings = openings;
    }

    // Select the latest evidence, including a corrected final count, if any attempt was discrepant.
    private static final String CLOSING_PREDICATE = """
            and not exists (select 1 from intento_arqueo_caja newer
                where newer.sesion_caja_id=a.sesion_caja_id and newer.numero_intento>a.numero_intento)
            and exists (select 1 from intento_arqueo_caja evidence
                where evidence.sesion_caja_id=a.sesion_caja_id and evidence.descuadre <> 0)
            """;

    private static final String SOURCES = """
            select s.id, s.id session_id, s.tienda_id, s.terminal_id, s.usuario_apertura_id user_id,
                   'OPENING' type, s.abierta_en occurred_at, null::integer attempt_number,
                   null::boolean session_closed, s.apertura_esperado expected_fund,
                   s.apertura_contado counted_fund, s.apertura_diferencia difference,
                   s.apertura_revisada_por reviewer_id, s.apertura_revisada_en reviewed_at,
                   s.apertura_comentario_revision comment, s.apertura_revision_version version
            from sesion_caja s where s.tienda_id=:storeId and s.apertura_diferencia <> 0
            union all
            select a.id, s.id, s.tienda_id, s.terminal_id, a.usuario_id,
                   'CLOSING', a.creado_en, a.numero_intento, a.cerro_sesion,
                   a.efectivo_teorico, a.fondo_declarado, a.descuadre,
                   r.revisada_por, r.revisada_en, r.comentario, coalesce(r.version,0)
            from intento_arqueo_caja a join sesion_caja s on s.id=a.sesion_caja_id
            left join intento_arqueo_caja_revision r on r.intento_id=a.id
            where s.tienda_id=:storeId
            """ + CLOSING_PREDICATE;

    private static final String SELECT = """
            select a.*, t.nombre terminal_name, u.user_name username, u.nombre user_name,
                   r.user_name reviewer_username, r.nombre reviewer_name
            from (
            """ + SOURCES + """
            ) a join terminal t on t.id=a.terminal_id and t.tienda_id=a.tienda_id
            join usuario u on u.id=a.user_id
            left join usuario r on r.id=a.reviewer_id
            where a.tienda_id=:storeId
            """;

    public List<CashAlertView> find(UUID storeId, Instant from, Instant toExclusive, UUID terminalId,
            UUID userId, String status, String type, Cursor cursor, int limit) {
        var sql = new StringBuilder(SELECT);
        var params = new MapSqlParameterSource("storeId", storeId).addValue("limit", limit);
        if (from != null) { sql.append(" and a.occurred_at >= :from"); params.addValue("from", Timestamp.from(from)); }
        if (toExclusive != null) { sql.append(" and a.occurred_at < :to"); params.addValue("to", Timestamp.from(toExclusive)); }
        if (terminalId != null) { sql.append(" and a.terminal_id=:terminalId"); params.addValue("terminalId", terminalId); }
        if (userId != null) { sql.append(" and a.user_id=:userId"); params.addValue("userId", userId); }
        if (type != null) { sql.append(" and a.type=:type"); params.addValue("type", type); }
        if (status != null) sql.append(status.equals("PENDING") ? " and a.reviewed_at is null" : " and a.reviewed_at is not null");
        if (cursor != null) {
            sql.append(" and (a.occurred_at,a.id,a.type) < (:cursorAt,:cursorId,:cursorType)");
            params.addValue("cursorAt", Timestamp.from(cursor.at())).addValue("cursorId", cursor.id())
                    .addValue("cursorType", cursor.type());
        }
        sql.append(" order by a.occurred_at desc,a.id desc,a.type desc limit :limit");
        return jdbc.query(sql.toString(), params, (r, n) -> map(r));
    }

    public Optional<CashAlertView> findById(UUID storeId, UUID id, String type) {
        return jdbc.query(SELECT + " and a.id=:id and a.type=:type",
                new MapSqlParameterSource("storeId", storeId).addValue("id", id).addValue("type", type),
                (r, n) -> map(r)).stream().findFirst();
    }

    public long pendingCount(UUID storeId) {
        return jdbc.queryForObject("select count(*) from (" + SOURCES + ") a where a.reviewed_at is null",
                new MapSqlParameterSource("storeId", storeId), Long.class);
    }

    public List<CashAlertAttemptView> findAttempts(UUID storeId, UUID sessionId) {
        return jdbc.query("""
                select a.id,a.numero_intento,a.creado_en,a.usuario_id,u.user_name username,u.nombre user_name,
                       a.efectivo_teorico,a.fondo_declarado,a.descuadre,a.cerro_sesion
                from intento_arqueo_caja a join sesion_caja s on s.id=a.sesion_caja_id
                join usuario u on u.id=a.usuario_id
                where s.tienda_id=:storeId and s.id=:sessionId
                order by a.numero_intento asc
                """, new MapSqlParameterSource("storeId", storeId).addValue("sessionId", sessionId),
                (r, n) -> new CashAlertAttemptView(r.getObject("id", UUID.class), r.getInt("numero_intento"),
                        r.getTimestamp("creado_en").toInstant(), r.getObject("usuario_id", UUID.class),
                        r.getString("username"), r.getString("user_name"), r.getBigDecimal("efectivo_teorico"),
                        r.getBigDecimal("fondo_declarado"), r.getBigDecimal("descuadre"), r.getBoolean("cerro_sesion")));
    }

    public boolean review(UUID storeId, UUID id, String type, UUID reviewerId, Instant at,
            String comment, long expectedVersion) {
        if ("OPENING".equals(type)) return openings.review(storeId, id, reviewerId, at, comment, expectedVersion);
        if (!"CLOSING".equals(type) || expectedVersion != 0) return false;
        return jdbc.update("""
                insert into intento_arqueo_caja_revision(intento_id,revisada_por,revisada_en,comentario,version)
                select a.id,:reviewerId,:at,:comment,1 from intento_arqueo_caja a
                join sesion_caja s on s.id=a.sesion_caja_id
                where a.id=:id and s.tienda_id=:storeId
                """ + CLOSING_PREDICATE + """
                on conflict (intento_id) do nothing
                """, new MapSqlParameterSource("storeId", storeId).addValue("id", id)
                .addValue("reviewerId", reviewerId).addValue("at", Timestamp.from(at)).addValue("comment", comment)) == 1;
    }

    private static CashAlertView map(ResultSet r) throws SQLException {
        var reviewedAt = r.getTimestamp("reviewed_at");
        return new CashAlertView(r.getObject("id", UUID.class), r.getObject("session_id", UUID.class),
                r.getObject("terminal_id", UUID.class), r.getString("terminal_name"),
                r.getObject("user_id", UUID.class), r.getString("username"), r.getString("user_name"),
                r.getString("type"), r.getTimestamp("occurred_at").toInstant(),
                r.getObject("attempt_number", Integer.class), r.getObject("session_closed", Boolean.class),
                r.getBigDecimal("expected_fund"), r.getBigDecimal("counted_fund"), r.getBigDecimal("difference"),
                reviewedAt == null ? "PENDING" : "REVIEWED", r.getObject("reviewer_id", UUID.class),
                r.getString("reviewer_username"), r.getString("reviewer_name"),
                reviewedAt == null ? null : reviewedAt.toInstant(), r.getString("comment"), r.getLong("version"));
    }

    public record Cursor(Instant at, UUID id, String type) { }
}
