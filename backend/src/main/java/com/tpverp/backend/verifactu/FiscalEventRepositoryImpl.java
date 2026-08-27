package com.tpverp.backend.verifactu;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** JDBC keyset fragment. It intentionally projects metadata and never reads XML columns. */
class FiscalEventRepositoryImpl implements FiscalEventRepositoryCustom {
    private static final UUID MAX_UUID = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

    private final NamedParameterJdbcTemplate jdbc;

    FiscalEventRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public FiscalEventSummaryAggregate summarizeEvents(
            UUID companyId, UUID installationId, java.time.Instant now) {
        var parameters = new MapSqlParameterSource()
                .addValue("companyId", companyId)
                .addValue("installationId", installationId)
                .addValue("now", java.sql.Timestamp.from(now));
        return jdbc.queryForObject("""
                        with last_summary as (
                            select max(generado_en) as generado_en
                            from registro_evento_fiscal
                            where empresa_id = :companyId and instalacion_id = :installationId
                              and tipo_evento = '10'
                        )
                        select last_summary.generado_en as previous_summary_at,
                               count(evento.id) as event_count
                        from last_summary
                        left join registro_evento_fiscal evento
                          on evento.empresa_id = :companyId
                         and evento.instalacion_id = :installationId
                         and evento.tipo_evento <> '10'
                         and evento.generado_en > coalesce(
                             last_summary.generado_en, '-infinity'::timestamptz)
                         and evento.generado_en <= :now
                        group by last_summary.generado_en
                        """, parameters, (result, rowNumber) ->
                        new FiscalEventSummaryAggregate(
                                result.getTimestamp("previous_summary_at") == null
                                        ? null : result.getTimestamp("previous_summary_at").toInstant(),
                                result.getLong("event_count"), 0L, BigDecimal.ZERO,
                                BigDecimal.ZERO, 0L));
    }

    @Override
    public long maxSequenceForRead(UUID companyId, UUID installationId) {
        var value = jdbc.queryForObject("""
                        select coalesce(max(secuencia), 0)
                        from registro_evento_fiscal
                        where empresa_id = :companyId and instalacion_id = :installationId
                        """, scope(companyId, installationId), Long.class);
        return value == null ? 0L : value;
    }

    @Override
    public List<FiscalEventView> findCursorViewsForRead(
            UUID companyId, UUID installationId, long snapshotSequence,
            FiscalEventReadCursor cursor, int limit) {
        var parameters = scope(companyId, installationId)
                .addValue("snapshotSequence", snapshotSequence)
                .addValue("limit", limit);
        var where = "";
        var order = "desc";
        if (cursor == null || cursor.direction() == FiscalEventReadCursor.Direction.NEXT) {
            var anchorSequence = cursor == null ? Long.MAX_VALUE : cursor.anchorSequence();
            var anchorId = cursor == null ? MAX_UUID : cursor.anchorId();
            parameters.addValue("anchorSequence", anchorSequence).addValue("anchorId", anchorId);
            where = " and (secuencia < :anchorSequence"
                    + " or (secuencia = :anchorSequence and id < :anchorId))";
        } else {
            parameters.addValue("anchorSequence", cursor.anchorSequence())
                    .addValue("anchorId", cursor.anchorId());
            where = " and (secuencia > :anchorSequence"
                    + " or (secuencia = :anchorSequence and id > :anchorId))";
            order = "asc";
        }
        return jdbc.query("""
                        select id, instalacion_id, version_sistema_id, secuencia,
                               tipo_evento, modo_fiscal, generado_en,
                               huella_evento_anterior, huella_evento, xml_hash,
                               true as firmado
                        from registro_evento_fiscal
                        where empresa_id = :companyId and instalacion_id = :installationId
                          and secuencia <= :snapshotSequence
                        """ + where + " order by secuencia " + order + ", id " + order
                        + " limit :limit", parameters, (result, rowNumber) -> new FiscalEventView(
                                result.getObject("id", UUID.class),
                                result.getObject("instalacion_id", UUID.class),
                                result.getObject("version_sistema_id", UUID.class),
                                result.getLong("secuencia"),
                                FiscalEventType.valueOf(result.getString("tipo_evento")),
                                FiscalMode.valueOf(result.getString("modo_fiscal")),
                                result.getTimestamp("generado_en").toInstant(),
                                result.getString("huella_evento_anterior"),
                                result.getString("huella_evento"),
                                result.getString("xml_hash"),
                                result.getBoolean("firmado")));
    }

    private static MapSqlParameterSource scope(UUID companyId, UUID installationId) {
        return new MapSqlParameterSource()
                .addValue("companyId", companyId)
                .addValue("installationId", installationId);
    }
}
