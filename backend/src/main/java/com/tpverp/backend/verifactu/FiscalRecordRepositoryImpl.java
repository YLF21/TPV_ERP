package com.tpverp.backend.verifactu;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Aggregate-only fiscal-record repository fragment for periodic event summaries. */
class FiscalRecordRepositoryImpl implements FiscalRecordSummaryRepositoryCustom {
    private final NamedParameterJdbcTemplate jdbc;

    FiscalRecordRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public FiscalEventSummaryAggregate summarizePeriod(
            UUID companyId, UUID installationId, Instant previousSummaryAt, Instant now) {
        var values = jdbc.queryForObject("""
                        select count(*) filter (where operacion = 'ALTA') as altas,
                               coalesce(sum(cuota_total) filter (where operacion = 'ALTA'), 0)
                                   as total_tax,
                               coalesce(sum(importe_total) filter (where operacion = 'ALTA'), 0)
                                   as total_amount,
                               count(*) filter (where operacion = 'ANULACION') as anulaciones
                        from registro_fiscal
                        where empresa_id = :companyId and instalacion_id = :installationId
                          and modo_fiscal = 'NO_VERIFACTU'
                          and (:previousSummaryAt is null or generado_en > :previousSummaryAt)
                          and generado_en <= :now
                        """, new MapSqlParameterSource()
                                .addValue("companyId", companyId)
                                .addValue("installationId", installationId)
                                .addValue("previousSummaryAt", previousSummaryAt == null
                                        ? null : Timestamp.from(previousSummaryAt))
                                .addValue("now", Timestamp.from(now)), (result, rowNumber) ->
                        new FiscalEventSummaryAggregate(
                                previousSummaryAt, 0L, result.getLong("altas"),
                                result.getBigDecimal("total_tax"),
                                result.getBigDecimal("total_amount"),
                                result.getLong("anulaciones")));
        return values;
    }
}
