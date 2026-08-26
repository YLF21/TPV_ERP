package com.tpverp.backend.verifactu;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** JDBC keyset fragment for AEAT requirement metadata only. */
class FiscalRequiredSubmissionRepositoryImpl implements FiscalRequiredSubmissionRepositoryCustom {
    private final NamedParameterJdbcTemplate jdbc;

    FiscalRequiredSubmissionRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<FiscalRequiredSubmissionHistoryView> findLegacyHistoryPage(
            UUID companyId, UUID installationId, int limit) {
        return query("", "desc", new MapSqlParameterSource()
                .addValue("companyId", companyId).addValue("installationId", installationId)
                .addValue("limit", limit));
    }

    @Override
    public List<FiscalRequiredSubmissionHistoryView> findHistoryCursorPage(
            UUID companyId, UUID installationId, FiscalHistoryReadCursor cursor, int limit) {
        var parameters = new MapSqlParameterSource()
                .addValue("companyId", companyId)
                .addValue("installationId", installationId)
                .addValue("limit", limit);
        var order = "desc";
        var where = "";
        if (cursor != null && cursor.direction() == FiscalHistoryReadCursor.Direction.NEXT) {
            parameters.addValue("anchorTimestamp", cursor.anchorTimestamp())
                    .addValue("anchorId", cursor.anchorId());
            where = " and (solicitado_en < :anchorTimestamp"
                    + " or (solicitado_en = :anchorTimestamp and id < :anchorId))";
        } else if (cursor != null) {
            parameters.addValue("anchorTimestamp", cursor.anchorTimestamp())
                    .addValue("anchorId", cursor.anchorId());
            where = " and (solicitado_en > :anchorTimestamp"
                    + " or (solicitado_en = :anchorTimestamp and id > :anchorId))";
            order = "asc";
        }
        return query(where, order, parameters);
    }

    private List<FiscalRequiredSubmissionHistoryView> query(
            String where, String order, MapSqlParameterSource parameters) {
        return jdbc.query("""
                        select id, empresa_id, instalacion_id, referencia, solicitado_en,
                               atendido_en, exportacion_id, estado
                        from requerimiento_fiscal
                        where empresa_id = :companyId and instalacion_id = :installationId
                        """ + where + " order by solicitado_en " + order + ", id " + order
                        + " limit :limit", parameters, (result, rowNumber) ->
                                new FiscalRequiredSubmissionHistoryView(
                                        result.getObject("id", UUID.class),
                                        result.getObject("empresa_id", UUID.class),
                                        result.getObject("instalacion_id", UUID.class),
                                        result.getString("referencia"),
                                        result.getTimestamp("solicitado_en").toInstant(),
                                        instantOrNull(result.getTimestamp("atendido_en")),
                                        result.getObject("exportacion_id", UUID.class),
                                        result.getString("estado")));
    }

    private static java.time.Instant instantOrNull(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
