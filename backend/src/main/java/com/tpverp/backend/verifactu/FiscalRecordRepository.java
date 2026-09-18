package com.tpverp.backend.verifactu;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FiscalRecordRepository extends JpaRepository<FiscalRecord, UUID>,
        FiscalRecordSummaryRepositoryCustom {

    List<FiscalRecord> findAllByChainIdOrderBySequence(UUID chainId);

    List<FiscalRecord> findAllByCompanyIdAndInstallationIdOrderBySequence(
            UUID companyId, UUID installationId);

    @Query("select r from FiscalRecord r where r.companyId = :companyId "
            + "and r.installationId = :installationId and r.sequence > :afterSequence "
            + "and r.sequence <= :maximumSequence order by r.sequence asc")
    List<FiscalRecord> findIntegrityBatch(
            @Param("companyId") UUID companyId,
            @Param("installationId") UUID installationId,
            @Param("afterSequence") long afterSequence,
            @Param("maximumSequence") long maximumSequence,
            Pageable pageable);

    List<FiscalRecord> findAllByCompanyIdAndStoreIdAndInstallationIdOrderBySequence(
            UUID companyId, UUID storeId, UUID installationId);

    /**
     * Bounded candidate query for the synchronous compatibility export.  All
     * predicates which can affect the result are pushed to the database so
     * PageRequest(0, 1001) is sufficient to detect the hard compatibility cap
     * without loading the complete fiscal chain.
     */
    @Query("select r from FiscalRecord r where r.companyId = :companyId "
            + "and r.installationId = :installationId "
            + "and (:storeId is null or r.storeId = :storeId) "
            + "and (:periodStart is null or r.generatedAt >= :periodStart) "
            + "and (:periodEnd is null or r.generatedAt <= :periodEnd) "
            + "and (:dateFrom is null or r.issueDate >= :dateFrom) "
            + "and (:dateTo is null or r.issueDate <= :dateTo) "
            + "and (:documentNumber is null or lower(r.number) like concat('%', :documentNumber, '%')) "
            + "and (:operation is null or r.operation = :operation) "
            + "and (:documentType is null or r.documentType = :documentType) "
            + "and (:fiscalMode is null or r.fiscalMode = :fiscalMode) "
            + "and (:automaticModeFilter = false or :currentMode <> com.tpverp.backend.verifactu.FiscalMode.NO_VERIFACTU "
            + "or r.fiscalMode = com.tpverp.backend.verifactu.FiscalMode.NO_VERIFACTU) "
            + "order by r.sequence asc")
    List<FiscalRecord> findExportBatchByFilters(
            @Param("companyId") UUID companyId,
            @Param("storeId") UUID storeId,
            @Param("installationId") UUID installationId,
            @Param("periodStart") java.time.Instant periodStart,
            @Param("periodEnd") java.time.Instant periodEnd,
            @Param("dateFrom") java.time.LocalDate dateFrom,
            @Param("dateTo") java.time.LocalDate dateTo,
            @Param("documentNumber") String documentNumber,
            @Param("operation") FiscalRecordOperation operation,
            @Param("documentType") FiscalDocumentType documentType,
            @Param("fiscalMode") FiscalMode fiscalMode,
            @Param("automaticModeFilter") boolean automaticModeFilter,
            @Param("currentMode") FiscalMode currentMode,
            Pageable pageable);

    List<FiscalRecord> findByCompanyIdAndInstallationIdAndIdInOrderBySequenceAsc(
            UUID companyId, UUID installationId, java.util.Collection<UUID> ids);

    List<FiscalRecord> findByCompanyIdAndStoreIdAndInstallationIdAndIdInOrderBySequenceAsc(
            UUID companyId, UUID storeId, UUID installationId, java.util.Collection<UUID> ids);

    long countByCompanyIdAndStoreIdAndInstallationId(UUID companyId, UUID storeId, UUID installationId);

    @Query(value = "select exists(select 1 from registro_fiscal where empresa_id = :companyId and instalacion_id = :installationId and secuencia > :sequence)", nativeQuery = true)
    boolean existsByCompanyIdAndInstallationIdAndSequenceGreaterThan(
            @Param("companyId") UUID companyId, @Param("installationId") UUID installationId,
            @Param("sequence") long sequence);

    Optional<FiscalRecord> findTopByCompanyIdAndInstallationIdOrderBySequenceDesc(
            UUID companyId, UUID installationId);

    /**
     * A document may have several ALTA records after a correction.  Callers
     * which need the immutable source must always resolve the first one in
     * fiscal-chain order instead of relying on an ambiguous Optional query.
     */
    Optional<FiscalRecord> findFirstByDocumentIdAndOperationOrderBySequenceAsc(
            UUID documentId, FiscalRecordOperation operation);

    @Query(value = """
            select correction.*
              from registro_fiscal correction
              join registro_fiscal_relacion relation
                on relation.registro_id = correction.id
              join registro_fiscal original on original.id = :originalId
             where correction.cadena_id = original.cadena_id
               and (correction.documento_id = original.documento_id
                    or (original.documento_id is null and relation.relacionado_id = original.id))
               and relation.tipo = 'SUBSANA'
               and correction.snapshot ->> 'subsanacionIdempotencyKey' = :idempotencyKey
             order by correction.secuencia desc
             limit 1
            """, nativeQuery = true)
    Optional<FiscalRecord> findCorrectionByOriginalIdAndIdempotencyKey(
            @Param("originalId") UUID originalId,
            @Param("idempotencyKey") String idempotencyKey);

    @Query(value = """
            select correction.*
              from registro_fiscal correction
              join registro_fiscal_relacion relation
                on relation.registro_id = correction.id
              join registro_fiscal original on original.id = :originalId
             where correction.cadena_id = original.cadena_id
               and (correction.documento_id = original.documento_id
                    or (original.documento_id is null and relation.relacionado_id = original.id))
               and relation.tipo = 'SUBSANA'
             order by correction.secuencia desc
             limit 1
            """, nativeQuery = true)
    Optional<FiscalRecord> findLatestCorrectionByOriginalId(@Param("originalId") UUID originalId);

    Optional<FiscalRecord> findByIdAndCompanyIdAndStoreId(
            UUID id, UUID companyId, UUID storeId);

    Optional<FiscalRecord> findByIdAndCompanyIdAndInstallationId(
            UUID id, UUID companyId, UUID installationId);
}
