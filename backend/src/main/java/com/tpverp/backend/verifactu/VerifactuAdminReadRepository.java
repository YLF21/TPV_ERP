package com.tpverp.backend.verifactu;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class VerifactuAdminReadRepository {

    private static final String BASE_FROM = """
            from estado_envio_fiscal state
            join registro_fiscal record on record.id = state.registro_id
            where record.empresa_id = :companyId
              and record.tienda_id = :storeId
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public VerifactuAdminReadRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public VerifactuAdminSubmissionPage findSubmissions(
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
        var query = filteredQuery(
                companyId, storeId, updatedFrom, updatedToExclusive,
                status, documentType, operation, documentNumber);
        var total = jdbc.queryForObject(
                "select count(*) " + query.sql(), query.parameters(), Long.class);
        var totalElements = total == null ? 0L : total;
        var totalPages = totalElements == 0
                ? 0
                : (int) Math.min(Integer.MAX_VALUE, (totalElements + size - 1) / size);
        if (totalElements == 0 || (long) page * size >= totalElements) {
            return new VerifactuAdminSubmissionPage(List.of(), page, size, totalElements, totalPages);
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
                               state.estado,
                               state.actualizado_en,
                               state.ultimo_error_codigo
                        """ + query.sql() + """
                        order by state.actualizado_en desc, record.secuencia desc, record.id desc
                        limit :limit offset :offset
                        """,
                parameters,
                (result, rowNumber) -> new VerifactuAdminSubmissionView(
                        result.getObject("record_id", UUID.class),
                        result.getLong("secuencia"),
                        result.getString("serie_numero"),
                        FiscalDocumentType.valueOf(result.getString("tipo_documento_fiscal")),
                        FiscalRecordOperation.valueOf(result.getString("operacion")),
                        FiscalSubmissionStatus.valueOf(result.getString("estado")),
                        result.getTimestamp("actualizado_en").toInstant(),
                        result.getString("ultimo_error_codigo")));
        return new VerifactuAdminSubmissionPage(items, page, size, totalElements, totalPages);
    }

    public Map<FiscalSubmissionStatus, Long> countByStatus(UUID companyId, UUID storeId) {
        var parameters = scope(companyId, storeId);
        var counts = new EnumMap<FiscalSubmissionStatus, Long>(FiscalSubmissionStatus.class);
        var rows = jdbc.query("""
                        select state.estado, count(*) as total
                        """ + BASE_FROM + " group by state.estado",
                parameters,
                (result, rowNumber) -> Map.entry(
                        FiscalSubmissionStatus.valueOf(result.getString("estado")),
                        result.getLong("total")));
        rows.forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
        return counts;
    }

    public Instant findOldestPendingAt(UUID companyId, UUID storeId) {
        var parameters = scope(companyId, storeId)
                .addValue("pendingStatus", FiscalSubmissionStatus.PENDIENTE.name());
        return jdbc.queryForObject("""
                        select min(state.actualizado_en) as oldest_pending_at
                        """ + BASE_FROM + " and state.estado = :pendingStatus",
                parameters,
                (result, rowNumber) -> {
                    Timestamp timestamp = result.getTimestamp("oldest_pending_at");
                    return timestamp == null ? null : timestamp.toInstant();
                });
    }

    private FilteredQuery filteredQuery(
            UUID companyId,
            UUID storeId,
            Instant updatedFrom,
            Instant updatedToExclusive,
            FiscalSubmissionStatus status,
            FiscalDocumentType documentType,
            FiscalRecordOperation operation,
            String documentNumber) {
        var sql = new StringBuilder(BASE_FROM);
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
            parameters.addValue("documentNumber", "%" + escapeLike(documentNumber.toLowerCase()) + "%");
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

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private record FilteredQuery(String sql, MapSqlParameterSource parameters) {
    }
}
