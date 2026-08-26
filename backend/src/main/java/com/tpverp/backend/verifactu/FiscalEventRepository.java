package com.tpverp.backend.verifactu;

import java.util.List;
import java.util.UUID;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FiscalEventRepository extends JpaRepository<FiscalEvent, UUID>,
        FiscalEventRepositoryCustom {
    List<FiscalEvent> findTop50ByCompanyIdAndInstallationIdOrderByGeneratedAtDesc(
            UUID companyId, UUID installationId);
    List<FiscalEvent> findAllByCompanyIdAndInstallationIdOrderBySequenceAsc(
            UUID companyId, UUID installationId);
    @Query("select e from FiscalEvent e where e.companyId = :companyId "
            + "and e.installationId = :installationId and e.sequence > :afterSequence "
            + "and e.sequence <= :maximumSequence order by e.sequence asc")
    List<FiscalEvent> findIntegrityBatch(
            @Param("companyId") UUID companyId,
            @Param("installationId") UUID installationId,
            @Param("afterSequence") long afterSequence,
            @Param("maximumSequence") long maximumSequence,
            Pageable pageable);

    /** Lightweight projection used by the APP GESTIÓN event list. */
    @Query("select new com.tpverp.backend.verifactu.FiscalEventView("
            + "e.id, e.installationId, e.systemVersionId, e.sequence, e.type, "
            + "e.fiscalMode, e.generatedAt, e.previousHash, e.hash, e.xmlHash, "
            + "case when e.signedXml is not null and e.signedXml <> '' then true else false end) "
            + "from FiscalEvent e "
            + "where e.companyId = :companyId and e.installationId = :installationId "
            + "order by e.generatedAt desc, e.sequence desc, e.id desc")
    List<FiscalEventView> findTop50ViewsByCompanyIdAndInstallationId(
            UUID companyId, UUID installationId, Pageable pageable);
    /**
     * Bounded event references for the compatibility export.  The pageable is
     * intentionally part of the query contract: legacy callers must never
     * materialize an installation's complete event chain just to discover that
     * it should use the durable export job endpoint.
     */
    @Query("select e.id as id, e.generatedAt as generatedAt from FiscalEvent e "
            + "where e.companyId = :companyId and e.installationId = :installationId "
            + "and (:periodStart is null or e.generatedAt >= :periodStart) "
            + "and (:periodEnd is null or e.generatedAt <= :periodEnd) "
            + "order by e.sequence asc")
    List<FiscalEventExportReference> findExportReferencesByPeriod(
            @Param("companyId") UUID companyId,
            @Param("installationId") UUID installationId,
            @Param("periodStart") java.time.Instant periodStart,
            @Param("periodEnd") java.time.Instant periodEnd,
            Pageable pageable);

    /** Bounded entity variant used by the small synchronous compatibility path. */
    @Query("select e from FiscalEvent e "
            + "where e.companyId = :companyId and e.installationId = :installationId "
            + "and (:periodStart is null or e.generatedAt >= :periodStart) "
            + "and (:periodEnd is null or e.generatedAt <= :periodEnd) "
            + "order by e.sequence asc")
    List<FiscalEvent> findExportBatchByPeriod(
            @Param("companyId") UUID companyId,
            @Param("installationId") UUID installationId,
            @Param("periodStart") java.time.Instant periodStart,
            @Param("periodEnd") java.time.Instant periodEnd,
            Pageable pageable);
    Optional<FiscalEvent> findByIdAndCompanyIdAndInstallationId(
            UUID id, UUID companyId, UUID installationId);
    @Query("select e.signedXml from FiscalEvent e where e.id = :id "
            + "and e.companyId = :companyId and e.installationId = :installationId")
    Optional<String> findSignedXmlByIdAndCompanyIdAndInstallationId(
            UUID id, UUID companyId, UUID installationId);
    Optional<FiscalEvent> findTopByCompanyIdAndInstallationIdOrderBySequenceDesc(
            UUID companyId, UUID installationId);

    @Query(value = "select exists(select 1 from registro_evento_fiscal where empresa_id = :companyId and instalacion_id = :installationId and secuencia > :sequence)", nativeQuery = true)
    boolean existsByCompanyIdAndInstallationIdAndSequenceGreaterThan(
            @org.springframework.data.repository.query.Param("companyId") UUID companyId,
            @org.springframework.data.repository.query.Param("installationId") UUID installationId,
            @org.springframework.data.repository.query.Param("sequence") long sequence);

    interface FiscalEventExportReference {
        UUID getId();
        java.time.Instant getGeneratedAt();
    }
}
