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
public class CashOpeningAlertRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public CashOpeningAlertRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final String SELECT = """
            select s.id, s.terminal_id, t.nombre terminal_name, s.usuario_apertura_id,
                   u.user_name username, u.nombre user_name, s.abierta_en,
                   s.apertura_esperado, s.apertura_contado, s.apertura_diferencia,
                   s.apertura_revisada_por, r.user_name reviewer_username, r.nombre reviewer_name,
                   s.apertura_revisada_en, s.apertura_comentario_revision, s.apertura_revision_version
            from sesion_caja s
            join terminal t on t.id=s.terminal_id and t.tienda_id=s.tienda_id
            join usuario u on u.id=s.usuario_apertura_id
            left join usuario r on r.id=s.apertura_revisada_por
            where s.tienda_id=:storeId and s.apertura_diferencia <> 0
            """;

    public List<CashOpeningAlertView> find(UUID storeId, Instant from, Instant toExclusive,
            UUID terminalId, UUID userId, String status, Cursor cursor, int limit) {
        var sql = new StringBuilder(SELECT);
        var params = new MapSqlParameterSource("storeId", storeId).addValue("limit", limit);
        if (from != null) { sql.append(" and s.abierta_en >= :from"); params.addValue("from", Timestamp.from(from)); }
        if (toExclusive != null) { sql.append(" and s.abierta_en < :to"); params.addValue("to", Timestamp.from(toExclusive)); }
        if (terminalId != null) { sql.append(" and s.terminal_id=:terminalId"); params.addValue("terminalId", terminalId); }
        if (userId != null) { sql.append(" and s.usuario_apertura_id=:userId"); params.addValue("userId", userId); }
        if (status != null) sql.append(status.equals("PENDING") ? " and s.apertura_revisada_en is null" : " and s.apertura_revisada_en is not null");
        if (cursor != null) {
            sql.append(" and (s.abierta_en,s.id) < (:cursorAt,:cursorId)");
            params.addValue("cursorAt", Timestamp.from(cursor.at())).addValue("cursorId", cursor.id());
        }
        sql.append(" order by s.abierta_en desc,s.id desc limit :limit");
        return jdbc.query(sql.toString(), params, (rs, n) -> map(rs));
    }
    public Optional<CashOpeningAlertView> findById(UUID storeId, UUID id) {
        return jdbc.query(SELECT + " and s.id=:id", new MapSqlParameterSource("storeId", storeId).addValue("id", id),
                (rs, n) -> map(rs)).stream().findFirst();
    }
    public long pendingCount(UUID storeId) {
        return jdbc.queryForObject("select count(*) from sesion_caja where tienda_id=:storeId and apertura_diferencia <> 0 and apertura_revisada_en is null",
                new MapSqlParameterSource("storeId", storeId), Long.class);
    }
    public boolean review(UUID storeId, UUID id, UUID reviewerId, Instant at, String comment, long expectedVersion) {
        return jdbc.update("""
                update sesion_caja set apertura_revisada_por=:reviewerId, apertura_revisada_en=:at,
                  apertura_comentario_revision=:comment, apertura_revision_version=apertura_revision_version+1
                where tienda_id=:storeId and id=:id and apertura_diferencia <> 0
                  and apertura_revisada_en is null and apertura_revision_version=:expectedVersion
                """, new MapSqlParameterSource("storeId", storeId).addValue("id", id)
                .addValue("reviewerId", reviewerId).addValue("at", Timestamp.from(at)).addValue("comment", comment)
                .addValue("expectedVersion", expectedVersion)) == 1;
    }
    private static CashOpeningAlertView map(ResultSet r) throws SQLException {
        var reviewedAt = r.getTimestamp("apertura_revisada_en");
        return new CashOpeningAlertView(r.getObject("id", UUID.class), r.getObject("id", UUID.class),
                r.getObject("terminal_id", UUID.class), r.getString("terminal_name"),
                r.getObject("usuario_apertura_id", UUID.class), r.getString("username"), r.getString("user_name"),
                r.getTimestamp("abierta_en").toInstant(), r.getBigDecimal("apertura_esperado"),
                r.getBigDecimal("apertura_contado"), r.getBigDecimal("apertura_diferencia"),
                reviewedAt == null ? "PENDING" : "REVIEWED", r.getObject("apertura_revisada_por", UUID.class),
                r.getString("reviewer_username"), r.getString("reviewer_name"),
                reviewedAt == null ? null : reviewedAt.toInstant(), r.getString("apertura_comentario_revision"),
                r.getLong("apertura_revision_version"));
    }
    public record Cursor(Instant at, UUID id) { }
}
