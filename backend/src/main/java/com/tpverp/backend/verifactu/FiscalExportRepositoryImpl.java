package com.tpverp.backend.verifactu;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** JDBC keyset fragment for export metadata, excluding any payload/blob columns. */
class FiscalExportRepositoryImpl implements FiscalExportRepositoryCustom {
    private final NamedParameterJdbcTemplate jdbc;

    FiscalExportRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<FiscalExportHistoryView> findLegacyHistoryPage(
            UUID companyId, UUID installationId, int limit) {
        return query(companyId, installationId, "", "desc", new MapSqlParameterSource()
                .addValue("companyId", companyId).addValue("installationId", installationId)
                .addValue("limit", limit));
    }

    @Override
    public List<FiscalExportHistoryView> findHistoryCursorPage(
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
            where = " and (exportada_en < :anchorTimestamp"
                    + " or (exportada_en = :anchorTimestamp and id < :anchorId))";
        } else if (cursor != null) {
            parameters.addValue("anchorTimestamp", cursor.anchorTimestamp())
                    .addValue("anchorId", cursor.anchorId());
            where = " and (exportada_en > :anchorTimestamp"
                    + " or (exportada_en = :anchorTimestamp and id > :anchorId))";
            order = "asc";
        }
        return query(companyId, installationId, where, order, parameters);
    }

    private List<FiscalExportHistoryView> query(UUID companyId, UUID installationId,
            String where, String order, MapSqlParameterSource parameters) {
        return jdbc.query("""
                        select id, empresa_id, instalacion_id, tipo, exportada_en,
                               periodo_inicio, periodo_fin, numero_registros,
                               evento_id, contenido_hash
                        from exportacion_fiscal
                        where empresa_id = :companyId and instalacion_id = :installationId
                        """ + where + " order by exportada_en " + order + ", id " + order
                        + " limit :limit", parameters, (result, rowNumber) ->
                                new FiscalExportHistoryView(
                                        result.getObject("id", UUID.class),
                                        result.getObject("empresa_id", UUID.class),
                                        result.getObject("instalacion_id", UUID.class),
                                        FiscalExportKind.valueOf(result.getString("tipo")),
                                        result.getTimestamp("exportada_en").toInstant(),
                                        result.getObject("periodo_inicio", java.time.OffsetDateTime.class),
                                        result.getObject("periodo_fin", java.time.OffsetDateTime.class),
                                        result.getLong("numero_registros"),
                                        result.getObject("evento_id", UUID.class),
                                        result.getString("contenido_hash")));
    }
}
