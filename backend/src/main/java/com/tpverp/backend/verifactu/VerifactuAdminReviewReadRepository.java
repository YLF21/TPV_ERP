package com.tpverp.backend.verifactu;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class VerifactuAdminReviewReadRepository {

    private static final String DEFECTIVE_FROM = """
            from estado_envio_fiscal state
            join registro_fiscal record on record.id = state.registro_id
            where record.empresa_id = :companyId
              and record.tienda_id = :storeId
              and state.estado in ('RECHAZADO', 'DEFECTUOSO', 'ACEPTADO_CON_ERRORES')
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public VerifactuAdminReviewReadRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public VerifactuAdminDefectiveRecordPage findDefectiveRecords(
            UUID companyId,
            UUID storeId,
            Instant updatedFrom,
            Instant updatedToExclusive,
            FiscalSubmissionStatus status,
            FiscalDocumentType documentType,
            FiscalRecordOperation operation,
            String documentNumber,
            int page,
            int size) {
        var query = defectiveQuery(
                companyId, storeId, updatedFrom, updatedToExclusive,
                status, documentType, operation, documentNumber);
        long totalElements = valueOrZero(jdbc.queryForObject(
                "select count(*) " + query.sql(), query.parameters(), Long.class));
        int totalPages = totalPages(totalElements, size);
        if (totalElements == 0 || (long) page * size >= totalElements) {
            return new VerifactuAdminDefectiveRecordPage(
                    List.of(), page, size, totalElements, totalPages);
        }
        var parameters = query.parameters()
                .addValue("limit", size)
                .addValue("offset", (long) page * size);
        var items = jdbc.query("""
                        select record.id as record_id,
                               record.secuencia,
                               record.serie_numero,
                               record.tipo_documento_fiscal,
                               record.operacion,
                               record.fecha_expedicion,
                               state.estado,
                               state.actualizado_en,
                               state.ultimo_error_codigo
                        """ + query.sql() + """
                        order by state.actualizado_en desc, record.secuencia desc, record.id desc
                        limit :limit offset :offset
                        """,
                parameters,
                (result, rowNumber) -> new VerifactuAdminDefectiveRecordView(
                        result.getObject("record_id", UUID.class),
                        result.getLong("secuencia"),
                        result.getString("serie_numero"),
                        FiscalDocumentType.valueOf(result.getString("tipo_documento_fiscal")),
                        FiscalRecordOperation.valueOf(result.getString("operacion")),
                        result.getObject("fecha_expedicion", java.time.LocalDate.class),
                        FiscalSubmissionStatus.valueOf(result.getString("estado")),
                        result.getTimestamp("actualizado_en").toInstant(),
                        result.getString("ultimo_error_codigo")));
        return new VerifactuAdminDefectiveRecordPage(
                items, page, size, totalElements, totalPages);
    }

    public boolean recordExists(UUID companyId, UUID storeId, UUID recordId) {
        var total = jdbc.queryForObject("""
                        select count(*)
                        from registro_fiscal record
                        where record.id = :recordId
                          and record.empresa_id = :companyId
                          and record.tienda_id = :storeId
                        """,
                scope(companyId, storeId).addValue("recordId", recordId),
                Long.class);
        return valueOrZero(total) == 1;
    }

    public VerifactuAdminAttemptPage findAttempts(
            UUID companyId,
            UUID storeId,
            UUID recordId,
            int page,
            int size) {
        var parameters = scope(companyId, storeId).addValue("recordId", recordId);
        var from = """
                from intento_envio_fiscal attempt
                join registro_fiscal record on record.id = attempt.registro_id
                where record.id = :recordId
                  and record.empresa_id = :companyId
                  and record.tienda_id = :storeId
                """;
        long totalElements = valueOrZero(jdbc.queryForObject(
                "select count(*) " + from, parameters, Long.class));
        int totalPages = totalPages(totalElements, size);
        if (totalElements == 0 || (long) page * size >= totalElements) {
            return new VerifactuAdminAttemptPage(
                    List.of(), page, size, totalElements, totalPages);
        }
        parameters.addValue("limit", size).addValue("offset", (long) page * size);
        var items = jdbc.query("""
                        select attempt.id,
                               attempt.intentado_en,
                               attempt.estado,
                               attempt.error_codigo,
                               (attempt.error is not null
                                or attempt.xml_enviado is not null
                                or attempt.respuesta is not null) as has_technical_detail
                        """ + from + """
                        order by attempt.intentado_en desc, attempt.id desc
                        limit :limit offset :offset
                        """,
                parameters,
                (result, rowNumber) -> new VerifactuAdminAttemptView(
                        result.getObject("id", UUID.class),
                        result.getTimestamp("intentado_en").toInstant(),
                        FiscalSubmissionStatus.valueOf(result.getString("estado")),
                        result.getString("error_codigo"),
                        result.getBoolean("has_technical_detail")));
        return new VerifactuAdminAttemptPage(
                items, page, size, totalElements, totalPages);
    }

    public VerifactuAdminDiagnosticEvent findLastAttempt(UUID companyId, UUID storeId) {
        var items = jdbc.query("""
                        select attempt.intentado_en, attempt.estado
                        from intento_envio_fiscal attempt
                        join registro_fiscal record on record.id = attempt.registro_id
                        where record.empresa_id = :companyId
                          and record.tienda_id = :storeId
                        order by attempt.intentado_en desc, attempt.id desc
                        limit 1
                        """,
                scope(companyId, storeId),
                (result, rowNumber) -> new VerifactuAdminDiagnosticEvent(
                        result.getTimestamp("intentado_en").toInstant(),
                        FiscalSubmissionStatus.valueOf(result.getString("estado"))));
        return items.stream().findFirst().orElse(null);
    }

    private FilteredQuery defectiveQuery(
            UUID companyId,
            UUID storeId,
            Instant updatedFrom,
            Instant updatedToExclusive,
            FiscalSubmissionStatus status,
            FiscalDocumentType documentType,
            FiscalRecordOperation operation,
            String documentNumber) {
        var sql = new StringBuilder(DEFECTIVE_FROM);
        var parameters = scope(companyId, storeId);
        var filters = new ArrayList<String>();
        if (updatedFrom != null) {
            filters.add("state.actualizado_en >= :updatedFrom");
            parameters.addValue("updatedFrom", Timestamp.from(updatedFrom));
        }
        if (updatedToExclusive != null) {
            filters.add("state.actualizado_en < :updatedToExclusive");
            parameters.addValue("updatedToExclusive", Timestamp.from(updatedToExclusive));
        }
        if (status != null) {
            filters.add("state.estado = :status");
            parameters.addValue("status", status.name());
        }
        if (documentType != null) {
            filters.add("record.tipo_documento_fiscal = :documentType");
            parameters.addValue("documentType", documentType.name());
        }
        if (operation != null) {
            filters.add("record.operacion = :operation");
            parameters.addValue("operation", operation.name());
        }
        if (documentNumber != null) {
            filters.add("lower(record.serie_numero) like :documentNumber escape '\\'");
            parameters.addValue(
                    "documentNumber", "%" + escapeLike(documentNumber.toLowerCase()) + "%");
        }
        if (!filters.isEmpty()) {
            sql.append(" and ").append(String.join(" and ", filters)).append('\n');
        }
        return new FilteredQuery(sql.toString(), parameters);
    }

    private static MapSqlParameterSource scope(UUID companyId, UUID storeId) {
        return new MapSqlParameterSource()
                .addValue("companyId", companyId)
                .addValue("storeId", storeId);
    }

    private static int totalPages(long totalElements, int size) {
        return totalElements == 0
                ? 0
                : (int) Math.min(Integer.MAX_VALUE, (totalElements + size - 1) / size);
    }

    private static long valueOrZero(Long value) {
        return value == null ? 0 : value;
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private record FilteredQuery(String sql, MapSqlParameterSource parameters) {
    }
}
