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

    private static final int MAX_PAGE_SIZE = 100;
    /**
     * The operational queue is intentionally a bounded work set. Historical
     * outcomes remain available through the fiscal-record history view and
     * must not turn this endpoint into an unbounded archive scan.
     */
    private static final int MAX_OPERATIONAL_QUEUE_ROWS = 200;
    private static final List<String> ACTIVE_QUEUE_STATUSES = List.of(
            FiscalSubmissionStatus.PENDIENTE.name(),
            FiscalSubmissionStatus.ENVIANDO.name(),
            FiscalSubmissionStatus.ENVIADO.name(),
            FiscalSubmissionStatus.RECHAZADO.name());

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
        return findSubmissions(companyId, storeId, null, updatedFrom, updatedToExclusive,
                status, documentType, operation, documentNumber, page, size, null, null);
    }

    public VerifactuAdminSubmissionPage findSubmissions(
            UUID companyId, UUID storeId, UUID installationId,
            Instant updatedFrom, Instant updatedToExclusive,
            FiscalSubmissionStatus status, FiscalDocumentType documentType,
            FiscalRecordOperation operation, String documentNumber, int page, int size) {
        return findSubmissions(companyId, storeId, installationId, updatedFrom,
                updatedToExclusive, status, documentType, operation, documentNumber,
                page, size, null, null);
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
            int size,
            String sortBy,
            String sortDirection) {
        return findSubmissions(companyId, storeId, null, updatedFrom, updatedToExclusive,
                status, documentType, operation, documentNumber, page, size, sortBy,
                sortDirection);
    }

    public VerifactuAdminSubmissionPage findSubmissions(
            UUID companyId,
            UUID storeId,
            UUID installationId,
            Instant updatedFrom,
            Instant updatedToExclusive,
            FiscalSubmissionStatus status,
            FiscalDocumentType documentType,
            FiscalRecordOperation operation,
            String documentNumber,
            int page,
            int size,
            String sortBy,
            String sortDirection) {
        validatePage(page, size);
        var query = filteredQuery(
                companyId, storeId, installationId, updatedFrom, updatedToExclusive,
                status, documentType, operation, documentNumber);
        var countParameters = query.parameters().addValue(
                "queueProbeLimit", MAX_OPERATIONAL_QUEUE_ROWS + 1);
        var probedTotal = jdbc.queryForObject(
                "select count(*) from (select 1 " + query.sql()
                        + " limit :queueProbeLimit) bounded_queue",
                countParameters, Long.class);
        var totalRows = probedTotal == null ? 0L : probedTotal;
        var truncated = totalRows > MAX_OPERATIONAL_QUEUE_ROWS;
        var totalElements = Math.min(totalRows, MAX_OPERATIONAL_QUEUE_ROWS);
        var totalPages = totalElements == 0
                ? 0
                : (int) Math.min(Integer.MAX_VALUE, 1 + ((totalElements - 1) / size));
        if (totalElements == 0 || (long) page * size >= totalElements
                || (long) page * size >= MAX_OPERATIONAL_QUEUE_ROWS) {
            return new VerifactuAdminSubmissionPage(
                    List.of(), page, size, totalElements, totalPages, truncated);
        }

        var parameters = query.parameters()
                .addValue("limit", size)
                .addValue("offset", (long) page * size);
        var orderBy = submissionOrderBy(sortBy, sortDirection);
        var itemsSql = """
                        select record.id as record_id,
                               record.secuencia,
                               record.serie_numero,
                               record.tipo_documento_fiscal,
                               record.operacion,
                               state.estado,
                               state.actualizado_en,
                               state.ultimo_error_codigo
                        """ + query.sql()
                + "\norder by " + orderBy
                + "\nlimit :limit offset :offset";
        var items = jdbc.query(itemsSql,
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
        return new VerifactuAdminSubmissionPage(
                items, page, size, totalElements, totalPages, truncated);
    }

    private static String submissionOrderBy(String sortBy, String sortDirection) {
        if (sortBy == null || sortBy.isBlank()) {
            return "state.actualizado_en desc, record.secuencia desc, record.id desc";
        }
        var expression = switch (sortBy) {
            case "sequence" -> "record.secuencia";
            case "document" -> "lower(record.serie_numero)";
            case "fiscalOperation" -> "record.operacion";
            case "status" -> "state.estado";
            case "updatedAt" -> "state.actualizado_en";
            case "errorCode" -> "lower(coalesce(state.ultimo_error_codigo, ''))";
            default -> throw new IllegalArgumentException("sortBy no es valido");
        };
        var direction = "desc".equalsIgnoreCase(sortDirection) ? "desc" : "asc";
        return expression + " " + direction + ", record.id " + direction;
    }

    public Map<FiscalSubmissionStatus, Long> countByStatus(UUID companyId, UUID storeId) {
        return countByStatus(companyId, storeId, null);
    }

    public Map<FiscalSubmissionStatus, Long> countByStatus(
            UUID companyId, UUID storeId, UUID installationId) {
        var parameters = scope(companyId, storeId, installationId);
        var counts = new EnumMap<FiscalSubmissionStatus, Long>(FiscalSubmissionStatus.class);
        var scopedFrom = installationFilter(installationId);
        var rows = jdbc.query("""
                        select state.estado, count(*) as total
                        """ + BASE_FROM + scopedFrom + " group by state.estado",
                parameters,
                (result, rowNumber) -> Map.entry(
                        FiscalSubmissionStatus.valueOf(result.getString("estado")),
                        result.getLong("total")));
        rows.forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
        return counts;
    }

    public Instant findOldestPendingAt(UUID companyId, UUID storeId) {
        return findOldestPendingAt(companyId, storeId, null);
    }

    public Instant findOldestPendingAt(UUID companyId, UUID storeId, UUID installationId) {
        var parameters = scope(companyId, storeId, installationId)
                .addValue("pendingStatus", FiscalSubmissionStatus.PENDIENTE.name());
        var scopedFrom = installationFilter(installationId);
        return jdbc.queryForObject("""
                        select min(state.actualizado_en) as oldest_pending_at
                        """ + BASE_FROM + scopedFrom + " and state.estado = :pendingStatus",
                parameters,
                (result, rowNumber) -> {
                    Timestamp timestamp = result.getTimestamp("oldest_pending_at");
                    return timestamp == null ? null : timestamp.toInstant();
                });
    }

    private FilteredQuery filteredQuery(
            UUID companyId,
            UUID storeId,
            UUID installationId,
            Instant updatedFrom,
            Instant updatedToExclusive,
            FiscalSubmissionStatus status,
            FiscalDocumentType documentType,
            FiscalRecordOperation operation,
            String documentNumber) {
        var sql = new StringBuilder(BASE_FROM).append(installationFilter(installationId));
        var parameters = scope(companyId, storeId, installationId);
        var filters = new ArrayList<String>();
        if (updatedFrom != null) {
            filters.add("state.actualizado_en >= :updatedFrom");
            parameters.addValue("updatedFrom", Timestamp.from(updatedFrom));
        }
        if (updatedToExclusive != null) {
            filters.add("state.actualizado_en < :updatedToExclusive");
            parameters.addValue("updatedToExclusive", Timestamp.from(updatedToExclusive));
        }
        if (status == null) {
            filters.add("state.estado in (:activeQueueStatuses)");
            parameters.addValue("activeQueueStatuses", ACTIVE_QUEUE_STATUSES);
        } else if (ACTIVE_QUEUE_STATUSES.contains(status.name())) {
            filters.add("state.estado = :status");
            parameters.addValue("status", status.name());
        } else {
            // Keep old clients compatible while ensuring historical states
            // cannot be used to turn the operational queue into an archive.
            filters.add("1 = 0");
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

    private static MapSqlParameterSource scope(
            UUID companyId, UUID storeId, UUID installationId) {
        var parameters = new MapSqlParameterSource()
                .addValue("companyId", companyId)
                .addValue("storeId", storeId);
        if (installationId != null) {
            parameters.addValue("installationId", installationId);
        }
        return parameters;
    }

    private static String installationFilter(UUID installationId) {
        return installationId == null
                ? ""
                : " and record.instalacion_id = :installationId\n";
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private static void validatePage(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page no puede ser negativo");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size debe estar entre 1 y " + MAX_PAGE_SIZE);
        }
    }

    private record FilteredQuery(String sql, MapSqlParameterSource parameters) {
    }
}
