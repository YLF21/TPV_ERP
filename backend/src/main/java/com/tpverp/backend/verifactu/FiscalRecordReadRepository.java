package com.tpverp.backend.verifactu;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read-only SQL projections for fiscal records, including records without a send state. */
@Repository
public class FiscalRecordReadRepository {

    private static final String BASE_FROM = """
            from registro_fiscal record
            left join estado_envio_fiscal state on state.registro_id = record.id
            where record.empresa_id = :companyId
              and record.tienda_id = :storeId
              and record.instalacion_id = :installationId
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public FiscalRecordReadRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public FiscalRecordReadPage findPage(
            UUID companyId,
            UUID storeId,
            UUID installationId,
            LocalDate dateFrom,
            LocalDate dateTo,
            FiscalRecordOperation operation,
            FiscalDocumentType documentType,
            String documentNumber,
            FiscalMode fiscalMode,
            int page,
            int size) {
        return findPage(companyId, storeId, installationId, dateFrom, dateTo, operation,
                documentType, documentNumber, FiscalRecordNumberMatch.PREFIX, fiscalMode,
                page, size);
    }

    public FiscalRecordReadPage findPage(
            UUID companyId,
            UUID storeId,
            UUID installationId,
            LocalDate dateFrom,
            LocalDate dateTo,
            FiscalRecordOperation operation,
            FiscalDocumentType documentType,
            String documentNumber,
            FiscalRecordNumberMatch numberMatch,
            FiscalMode fiscalMode,
            int page,
            int size) {
        var query = filteredQuery(companyId, storeId, installationId, dateFrom, dateTo,
                operation, documentType, documentNumber, numberMatch, fiscalMode);
        long total = valueOrZero(jdbc.queryForObject(
                "select count(*) " + query.sql(), query.parameters(), Long.class));
        int totalPages = totalPages(total, size);
        if (total == 0 || (long) page * size >= total) {
            return new FiscalRecordReadPage(List.of(), page, size, total, totalPages);
        }

        var parameters = query.parameters()
                .addValue("limit", size)
                .addValue("offset", (long) page * size);
        var rows = jdbc.query("""
                        select record.id as record_id,
                               record.instalacion_id,
                               record.tienda_id,
                               record.documento_id,
                               record.secuencia,
                               record.operacion,
                               record.tipo_documento_fiscal,
                               record.serie_numero,
                               record.fecha_expedicion,
                               record.generado_en,
                               record.modo_fiscal,
                               record.cuota_total,
                               record.importe_total,
                               record.huella_anterior,
                               record.huella,
                               state.estado,
                               state.actualizado_en
                        """ + query.sql() + """
                        order by record.generado_en desc, record.secuencia desc, record.id desc
                        limit :limit offset :offset
                        """,
                parameters,
                (result, rowNumber) -> rowFrom(result));
        return new FiscalRecordReadPage(
                rows.stream().map(FiscalRecordReadView::from).toList(),
                page, size, total, totalPages);
    }

    public long maxSequence(UUID companyId, UUID storeId, UUID installationId) {
        var value = jdbc.queryForObject("""
                        select max(record.secuencia)
                        from registro_fiscal record
                        where record.empresa_id = :companyId
                          and record.tienda_id = :storeId
                          and record.instalacion_id = :installationId
                        """,
                scope(companyId, storeId, installationId), Long.class);
        return value == null ? 0L : value;
    }

    public List<Row> findCursorRows(
            UUID companyId,
            UUID storeId,
            UUID installationId,
            LocalDate dateFrom,
            LocalDate dateTo,
            FiscalRecordOperation operation,
            FiscalDocumentType documentType,
            String documentNumber,
            FiscalMode fiscalMode,
            long snapshotSequence,
            FiscalRecordReadCursor cursor,
            int limit) {
        return findCursorRows(companyId, storeId, installationId, dateFrom, dateTo, operation,
                documentType, documentNumber, FiscalRecordNumberMatch.PREFIX, fiscalMode,
                snapshotSequence, cursor, limit);
    }

    public List<Row> findCursorRows(
            UUID companyId,
            UUID storeId,
            UUID installationId,
            LocalDate dateFrom,
            LocalDate dateTo,
            FiscalRecordOperation operation,
            FiscalDocumentType documentType,
            String documentNumber,
            FiscalRecordNumberMatch numberMatch,
            FiscalMode fiscalMode,
            long snapshotSequence,
            FiscalRecordReadCursor cursor,
            int limit) {
        var query = filteredQuery(companyId, storeId, installationId, dateFrom, dateTo,
                operation, documentType, documentNumber, numberMatch, fiscalMode);
        query.parameters().addValue("snapshotSequence", snapshotSequence);
        var direction = cursor == null
                ? FiscalRecordReadCursor.Direction.NEXT : cursor.direction();
        if (cursor == null || direction == FiscalRecordReadCursor.Direction.NEXT) {
            query.parameters().addValue("anchorSequence",
                    cursor == null ? Long.MAX_VALUE : cursor.anchorSequence());
        } else {
            query.parameters().addValue("anchorSequence", cursor.anchorSequence());
        }
        var anchorComparison = direction == FiscalRecordReadCursor.Direction.NEXT ? "<" : ">";
        var order = direction == FiscalRecordReadCursor.Direction.NEXT
                ? "desc" : "asc";
        return jdbc.query("""
                        select record.id as record_id,
                               record.instalacion_id,
                               record.tienda_id,
                               record.documento_id,
                               record.secuencia,
                               record.operacion,
                               record.tipo_documento_fiscal,
                               record.serie_numero,
                               record.fecha_expedicion,
                               record.generado_en,
                               record.modo_fiscal,
                               record.cuota_total,
                               record.importe_total,
                               record.huella_anterior,
                               record.huella,
                               state.estado,
                               state.actualizado_en
                        """ + query.sql() + " and record.secuencia <= :snapshotSequence"
                        + " and record.secuencia " + anchorComparison
                        + " :anchorSequence order by record.secuencia " + order
                        + ", record.id " + order + " limit :limit",
                query.parameters().addValue("limit", limit),
                (result, rowNumber) -> rowFrom(result));
    }

    public Optional<Row> findDetail(
            UUID companyId, UUID storeId, UUID installationId, UUID recordId) {
        var rows = jdbc.query("""
                        select record.id as record_id,
                               record.cadena_id,
                               record.empresa_id,
                               record.instalacion_id,
                               record.tienda_id,
                               record.documento_id,
                               record.secuencia,
                               record.operacion,
                               record.tipo_documento_fiscal,
                               record.serie_numero,
                               record.fecha_expedicion,
                               record.generado_en,
                               record.zona_horaria,
                               record.nif_emisor,
                               record.cuota_total,
                               record.importe_total,
                               record.huella_anterior,
                               record.huella,
                               record.hash_snapshot,
                               record.version_formato,
                               record.version_algoritmo,
                               record.version_aplicacion,
                               record.modo_fiscal,
                               previous_record.id as previous_record_id,
                               previous_record.tienda_id as previous_record_store_id,
                               previous_record.huella as previous_record_hash,
                               previous_record.generado_en as previous_record_generated_at,
                               next_record.id as next_record_id,
                               next_record.tienda_id as next_record_store_id,
                               next_record.huella_anterior as next_record_previous_hash,
                               next_record.generado_en as next_record_generated_at,
                               state.estado,
                               state.actualizado_en,
                               state.ultimo_error_codigo,
                               document.id as document_id,
                               document.tienda_id as document_store_id,
                               document.tipo as document_type,
                               document.estado as document_status,
                               document.numero as document_number,
                               document.fecha as document_issue_date,
                               document.creado_en as document_created_at,
                               document.confirmado_en as document_confirmed_at,
                               document.anulado_en as document_cancelled_at,
                               artifact.modo_fiscal as artifact_mode,
                               artifact.entorno as artifact_environment,
                               artifact.sandbox as artifact_sandbox,
                               artifact.version_sistema_id as artifact_system_version_id,
                               artifact.obligado_nombre as artifact_issuer_name,
                               artifact.obligado_nif as artifact_issuer_tax_id,
                               artifact.xml_hash as artifact_xml_hash,
                               artifact.qr_url as artifact_qr_url,
                               artifact.qr_hash as artifact_qr_hash,
                               artifact.creado_en as artifact_created_at
                        from registro_fiscal record
                        left join registro_fiscal previous_record
                          on previous_record.cadena_id = record.cadena_id
                         and previous_record.secuencia = record.secuencia - 1
                         and previous_record.empresa_id = record.empresa_id
                         and previous_record.instalacion_id = record.instalacion_id
                        left join registro_fiscal next_record
                          on next_record.cadena_id = record.cadena_id
                         and next_record.secuencia = record.secuencia + 1
                         and next_record.empresa_id = record.empresa_id
                         and next_record.instalacion_id = record.instalacion_id
                        left join estado_envio_fiscal state on state.registro_id = record.id
                        left join documento document on document.id = record.documento_id
                        left join artefacto_registro_fiscal artifact
                          on artifact.registro_id = record.id
                        where record.id = :recordId
                          and record.empresa_id = :companyId
                          and record.tienda_id = :storeId
                          and record.instalacion_id = :installationId
                        """,
                new MapSqlParameterSource()
                        .addValue("recordId", recordId)
                        .addValue("companyId", companyId)
                        .addValue("storeId", storeId)
                        .addValue("installationId", installationId),
                (result, rowNumber) -> detailRowFrom(result));
        return rows.stream().findFirst();
    }

    public List<FiscalRecordRelationView> findRelations(
            UUID companyId, UUID storeId, UUID installationId, UUID recordId) {
        return jdbc.query("""
                        select relation.relacionado_id, relation.tipo
                        from registro_fiscal_relacion relation
                        join registro_fiscal related on related.id = relation.relacionado_id
                        where relation.registro_id = :recordId
                          and related.empresa_id = :companyId
                          and related.tienda_id = :storeId
                          and related.instalacion_id = :installationId
                        order by relation.tipo, relation.relacionado_id
                        """,
                new MapSqlParameterSource()
                        .addValue("recordId", recordId)
                        .addValue("companyId", companyId)
                        .addValue("storeId", storeId)
                        .addValue("installationId", installationId),
                (result, rowNumber) -> new FiscalRecordRelationView(
                        result.getObject("relacionado_id", UUID.class),
                        FiscalRelationType.valueOf(result.getString("tipo"))));
    }

    private FilteredQuery filteredQuery(
            UUID companyId,
            UUID storeId,
            UUID installationId,
            LocalDate dateFrom,
            LocalDate dateTo,
            FiscalRecordOperation operation,
            FiscalDocumentType documentType,
            String documentNumber,
            FiscalRecordNumberMatch numberMatch,
            FiscalMode fiscalMode) {
        var sql = new StringBuilder(BASE_FROM);
        var parameters = scope(companyId, storeId, installationId);
        var filters = new ArrayList<String>();
        if (dateFrom != null) {
            filters.add("record.fecha_expedicion >= :dateFrom");
            parameters.addValue("dateFrom", dateFrom);
        }
        if (dateTo != null) {
            filters.add("record.fecha_expedicion <= :dateTo");
            parameters.addValue("dateTo", dateTo);
        }
        if (operation != null) {
            filters.add("record.operacion = :operation");
            parameters.addValue("operation", operation.name());
        }
        if (documentType != null) {
            filters.add("record.tipo_documento_fiscal = :documentType");
            parameters.addValue("documentType", documentType.name());
        }
        if (documentNumber != null) {
            if (numberMatch == FiscalRecordNumberMatch.EXACT) {
                filters.add("lower(record.serie_numero) = :documentNumber");
                parameters.addValue("documentNumber", normalizedDocumentNumber(documentNumber));
            } else {
                filters.add("lower(record.serie_numero) like :documentNumberPrefix escape '\\'");
                parameters.addValue("documentNumberPrefix", documentNumberPrefix(documentNumber));
            }
        }
        if (fiscalMode != null) {
            filters.add("record.modo_fiscal = :fiscalMode");
            parameters.addValue("fiscalMode", fiscalMode.name());
        }
        if (!filters.isEmpty()) {
            sql.append(" and ").append(String.join(" and ", filters)).append('\n');
        }
        return new FilteredQuery(sql.toString(), parameters);
    }

    private static Row rowFrom(java.sql.ResultSet result) throws java.sql.SQLException {
        return new Row(
                result.getObject("record_id", UUID.class),
                result.getObject("instalacion_id", UUID.class),
                result.getObject("tienda_id", UUID.class),
                result.getObject("documento_id", UUID.class),
                result.getLong("secuencia"),
                FiscalRecordOperation.valueOf(result.getString("operacion")),
                FiscalDocumentType.valueOf(result.getString("tipo_documento_fiscal")),
                result.getString("serie_numero"),
                result.getObject("fecha_expedicion", LocalDate.class),
                result.getTimestamp("generado_en").toInstant(),
                FiscalMode.valueOf(result.getString("modo_fiscal")),
                result.getBigDecimal("cuota_total"),
                result.getBigDecimal("importe_total"),
                result.getString("huella_anterior"),
                result.getString("huella"),
                enumOrNull(result.getString("estado"), FiscalSubmissionStatus.class),
                instantOrNull(result.getTimestamp("actualizado_en")));
    }

    private static Row detailRowFrom(java.sql.ResultSet result) throws java.sql.SQLException {
        return new Row(
                result.getObject("record_id", UUID.class),
                result.getObject("cadena_id", UUID.class),
                result.getObject("empresa_id", UUID.class),
                result.getObject("instalacion_id", UUID.class),
                result.getObject("tienda_id", UUID.class),
                result.getObject("documento_id", UUID.class),
                result.getLong("secuencia"),
                FiscalRecordOperation.valueOf(result.getString("operacion")),
                FiscalDocumentType.valueOf(result.getString("tipo_documento_fiscal")),
                result.getString("serie_numero"),
                result.getObject("fecha_expedicion", LocalDate.class),
                result.getTimestamp("generado_en").toInstant(),
                result.getString("zona_horaria"),
                result.getString("nif_emisor"),
                result.getBigDecimal("cuota_total"),
                result.getBigDecimal("importe_total"),
                result.getString("huella_anterior"),
                result.getString("huella"),
                result.getString("hash_snapshot"),
                result.getString("version_formato"),
                result.getString("version_algoritmo"),
                result.getString("version_aplicacion"),
                FiscalMode.valueOf(result.getString("modo_fiscal")),
                result.getObject("previous_record_id", UUID.class),
                result.getObject("previous_record_store_id", UUID.class),
                result.getString("previous_record_hash"),
                instantOrNull(result.getTimestamp("previous_record_generated_at")),
                result.getObject("next_record_id", UUID.class),
                result.getObject("next_record_store_id", UUID.class),
                result.getString("next_record_previous_hash"),
                instantOrNull(result.getTimestamp("next_record_generated_at")),
                result.getObject("document_id", UUID.class),
                result.getObject("document_store_id", UUID.class),
                enumOrNull(result.getString("document_type"), com.tpverp.backend.document.CommercialDocumentType.class),
                enumOrNull(result.getString("document_status"), com.tpverp.backend.document.DocumentStatus.class),
                result.getString("document_number"),
                result.getObject("document_issue_date", LocalDate.class),
                instantOrNull(result.getTimestamp("document_created_at")),
                instantOrNull(result.getTimestamp("document_confirmed_at")),
                instantOrNull(result.getTimestamp("document_cancelled_at")),
                enumOrNull(result.getString("artifact_mode"), FiscalMode.class),
                enumOrNull(result.getString("artifact_environment"), FiscalEndpointEnvironment.class),
                result.getObject("artifact_system_version_id", UUID.class),
                result.getBoolean("artifact_sandbox"),
                result.getString("artifact_issuer_name"),
                result.getString("artifact_issuer_tax_id"),
                result.getString("artifact_xml_hash"),
                result.getString("artifact_qr_url"),
                result.getString("artifact_qr_hash"),
                instantOrNull(result.getTimestamp("artifact_created_at")),
                enumOrNull(result.getString("estado"), FiscalSubmissionStatus.class),
                instantOrNull(result.getTimestamp("actualizado_en")),
                result.getString("ultimo_error_codigo"));
    }

    private static MapSqlParameterSource scope(UUID companyId, UUID storeId, UUID installationId) {
        return new MapSqlParameterSource()
                .addValue("companyId", companyId)
                .addValue("storeId", storeId)
                .addValue("installationId", installationId);
    }

    private static Instant instantOrNull(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static <T extends Enum<T>> T enumOrNull(String value, Class<T> type) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    static String documentNumberPrefix(String value) {
        return escapeLike(value.trim().toLowerCase(Locale.ROOT)) + "%";
    }

    static String normalizedDocumentNumber(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static int totalPages(long total, int size) {
        return total == 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, (total + size - 1) / size);
    }

    private static long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private record FilteredQuery(String sql, MapSqlParameterSource parameters) {
    }

    /** Internal SQL projection used by both list and detail responses. */
    public static final class Row {
        private final UUID recordId;
        private final UUID installationId;
        private final UUID storeId;
        private final UUID documentId;
        private final long sequence;
        private final FiscalRecordOperation operation;
        private final FiscalDocumentType documentType;
        private final String number;
        private final LocalDate issueDate;
        private final Instant generatedAt;
        private final FiscalMode fiscalMode;
        private final java.math.BigDecimal totalTax;
        private final java.math.BigDecimal totalAmount;
        private final String previousHash;
        private final String hash;
        private final FiscalSubmissionStatus submissionStatus;
        private final Instant submissionUpdatedAt;
        private Detail detail;

        private Row(
                UUID recordId,
                UUID installationId,
                UUID storeId,
                UUID documentId,
                long sequence,
                FiscalRecordOperation operation,
                FiscalDocumentType documentType,
                String number,
                LocalDate issueDate,
                Instant generatedAt,
                FiscalMode fiscalMode,
                java.math.BigDecimal totalTax,
                java.math.BigDecimal totalAmount,
                String previousHash,
                String hash,
                FiscalSubmissionStatus submissionStatus,
                Instant submissionUpdatedAt) {
            this.recordId = recordId;
            this.installationId = installationId;
            this.storeId = storeId;
            this.documentId = documentId;
            this.sequence = sequence;
            this.operation = operation;
            this.documentType = documentType;
            this.number = number;
            this.issueDate = issueDate;
            this.generatedAt = generatedAt;
            this.fiscalMode = fiscalMode;
            this.totalTax = totalTax;
            this.totalAmount = totalAmount;
            this.previousHash = previousHash;
            this.hash = hash;
            this.submissionStatus = submissionStatus;
            this.submissionUpdatedAt = submissionUpdatedAt;
        }

        private Row(
                UUID recordId,
                UUID chainId,
                UUID companyId,
                UUID installationId,
                UUID storeId,
                UUID documentId,
                long sequence,
                FiscalRecordOperation operation,
                FiscalDocumentType documentType,
                String number,
                LocalDate issueDate,
                Instant generatedAt,
                String timezone,
                String issuerTaxId,
                java.math.BigDecimal totalTax,
                java.math.BigDecimal totalAmount,
                String previousHash,
                String hash,
                String snapshotHash,
                String formatVersion,
                String algorithmVersion,
                String applicationVersion,
                FiscalMode fiscalMode,
                UUID previousRecordId,
                UUID previousRecordStoreId,
                String previousRecordHash,
                Instant previousRecordGeneratedAt,
                UUID nextRecordId,
                UUID nextRecordStoreId,
                String nextRecordPreviousHash,
                Instant nextRecordGeneratedAt,
                UUID documentId2,
                UUID documentStoreId,
                com.tpverp.backend.document.CommercialDocumentType documentType2,
                com.tpverp.backend.document.DocumentStatus documentStatus,
                String documentNumber,
                LocalDate documentIssueDate,
                Instant documentCreatedAt,
                Instant documentConfirmedAt,
                Instant documentCancelledAt,
                FiscalMode artifactMode,
                FiscalEndpointEnvironment artifactEnvironment,
                UUID artifactSystemVersionId,
                boolean artifactSandbox,
                String artifactIssuerName,
                String artifactIssuerTaxId,
                String artifactXmlHash,
                String artifactQrUrl,
                String artifactQrHash,
                Instant artifactCreatedAt,
                FiscalSubmissionStatus submissionStatus,
                Instant submissionUpdatedAt,
                String submissionErrorCode) {
            this(recordId, installationId, storeId, documentId, sequence, operation, documentType,
                    number, issueDate, generatedAt, fiscalMode, totalTax, totalAmount,
                    previousHash, hash, submissionStatus, submissionUpdatedAt);
            this.detail = new Detail(chainId, companyId, timezone, issuerTaxId, snapshotHash,
                    formatVersion, algorithmVersion, applicationVersion, previousRecordId,
                    previousRecordStoreId, previousRecordHash, previousRecordGeneratedAt,
                    nextRecordId, nextRecordStoreId, nextRecordPreviousHash, nextRecordGeneratedAt,
                    documentId2, documentStoreId,
                    documentType2, documentStatus,
                    documentNumber, documentIssueDate, documentCreatedAt, documentConfirmedAt,
                    documentCancelledAt, artifactMode, artifactEnvironment, artifactSystemVersionId,
                    artifactSandbox, artifactIssuerName, artifactIssuerTaxId, artifactXmlHash,
                    artifactQrUrl, artifactQrHash, artifactCreatedAt, submissionErrorCode);
        }

        public UUID recordId() { return recordId; }
        public UUID installationId() { return installationId; }
        public UUID storeId() { return storeId; }
        public UUID documentId() { return documentId; }
        public long sequence() { return sequence; }
        public FiscalRecordOperation operation() { return operation; }
        public FiscalDocumentType documentType() { return documentType; }
        public String number() { return number; }
        public LocalDate issueDate() { return issueDate; }
        public Instant generatedAt() { return generatedAt; }
        public FiscalMode fiscalMode() { return fiscalMode; }
        public java.math.BigDecimal totalTax() { return totalTax; }
        public java.math.BigDecimal totalAmount() { return totalAmount; }
        public String previousHash() { return previousHash; }
        public String hash() { return hash; }
        public FiscalSubmissionStatus submissionStatus() { return submissionStatus; }
        public Instant submissionUpdatedAt() { return submissionUpdatedAt; }
        public boolean isDetail() { return detail != null; }
        public Detail detail() { return detail; }
    }

    public record Detail(
            UUID chainId, UUID companyId, String timezone, String issuerTaxId,
            String snapshotHash, String formatVersion, String algorithmVersion,
            String applicationVersion, UUID previousRecordId, UUID previousRecordStoreId,
            String previousRecordHash, Instant previousRecordGeneratedAt, UUID nextRecordId,
            UUID nextRecordStoreId, String nextRecordPreviousHash, Instant nextRecordGeneratedAt,
            UUID documentId, UUID documentStoreId,
            com.tpverp.backend.document.CommercialDocumentType documentType,
            com.tpverp.backend.document.DocumentStatus documentStatus,
            String documentNumber, LocalDate documentIssueDate, Instant documentCreatedAt,
            Instant documentConfirmedAt, Instant documentCancelledAt, FiscalMode artifactMode,
            FiscalEndpointEnvironment artifactEnvironment, UUID artifactSystemVersionId,
            boolean artifactSandbox, String artifactIssuerName, String artifactIssuerTaxId,
            String artifactXmlHash, String artifactQrUrl, String artifactQrHash,
            Instant artifactCreatedAt, String submissionErrorCode) {
        /** Compatibility constructor for projections created before neighbor scope metadata. */
        public Detail(
                UUID chainId, UUID companyId, String timezone, String issuerTaxId,
                String snapshotHash, String formatVersion, String algorithmVersion,
                String applicationVersion, UUID previousRecordId, String previousRecordHash,
                Instant previousRecordGeneratedAt, UUID nextRecordId, String nextRecordPreviousHash,
                Instant nextRecordGeneratedAt, UUID documentId, UUID documentStoreId,
                com.tpverp.backend.document.CommercialDocumentType documentType,
                com.tpverp.backend.document.DocumentStatus documentStatus,
                String documentNumber, LocalDate documentIssueDate, Instant documentCreatedAt,
                Instant documentConfirmedAt, Instant documentCancelledAt, FiscalMode artifactMode,
                FiscalEndpointEnvironment artifactEnvironment, UUID artifactSystemVersionId,
                boolean artifactSandbox, String artifactIssuerName, String artifactIssuerTaxId,
                String artifactXmlHash, String artifactQrUrl, String artifactQrHash,
                Instant artifactCreatedAt, String submissionErrorCode) {
            this(chainId, companyId, timezone, issuerTaxId, snapshotHash, formatVersion,
                    algorithmVersion, applicationVersion, previousRecordId, null,
                    previousRecordHash, previousRecordGeneratedAt, nextRecordId, null,
                    nextRecordPreviousHash, nextRecordGeneratedAt, documentId, documentStoreId,
                    documentType, documentStatus, documentNumber, documentIssueDate,
                    documentCreatedAt, documentConfirmedAt, documentCancelledAt, artifactMode,
                    artifactEnvironment, artifactSystemVersionId, artifactSandbox,
                    artifactIssuerName, artifactIssuerTaxId, artifactXmlHash, artifactQrUrl,
                    artifactQrHash, artifactCreatedAt, submissionErrorCode);
        }

        public Detail(
                UUID chainId, UUID companyId, String timezone, String issuerTaxId,
                String snapshotHash, String formatVersion, String algorithmVersion,
                String applicationVersion, UUID previousRecordId, UUID nextRecordId,
                UUID documentId, UUID documentStoreId,
                com.tpverp.backend.document.CommercialDocumentType documentType,
                com.tpverp.backend.document.DocumentStatus documentStatus,
                String documentNumber, LocalDate documentIssueDate, Instant documentCreatedAt,
                Instant documentConfirmedAt, Instant documentCancelledAt, FiscalMode artifactMode,
                FiscalEndpointEnvironment artifactEnvironment, UUID artifactSystemVersionId,
                boolean artifactSandbox, String artifactIssuerName, String artifactIssuerTaxId,
                String artifactXmlHash, String artifactQrUrl, String artifactQrHash,
                Instant artifactCreatedAt, String submissionErrorCode) {
            this(chainId, companyId, timezone, issuerTaxId, snapshotHash, formatVersion,
                    algorithmVersion, applicationVersion, previousRecordId, null,
                    null, null, nextRecordId, null, null, null,
                    documentId, documentStoreId, documentType,
                    documentStatus, documentNumber, documentIssueDate, documentCreatedAt,
                    documentConfirmedAt, documentCancelledAt, artifactMode, artifactEnvironment,
                    artifactSystemVersionId, artifactSandbox, artifactIssuerName, artifactIssuerTaxId,
                    artifactXmlHash, artifactQrUrl, artifactQrHash, artifactCreatedAt,
                    submissionErrorCode);
        }
    }
}
