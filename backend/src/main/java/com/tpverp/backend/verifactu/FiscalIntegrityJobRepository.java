package com.tpverp.backend.verifactu;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface FiscalIntegrityJobRepository extends JpaRepository<FiscalIntegrityJob, UUID> {
    Optional<FiscalIntegrityJob> findByIdAndCompanyIdAndStoreIdAndInstallationId(
            UUID id, UUID companyId, UUID storeId, UUID installationId);

    Page<FiscalIntegrityJob> findAllByCompanyIdAndStoreIdAndInstallationIdAndRequestedByOrderByCreatedAtDesc(
            UUID companyId, UUID storeId, UUID installationId, String requestedBy, Pageable pageable);

    Page<FiscalIntegrityJob> findAllByCompanyIdAndStoreIdAndInstallationIdOrderByCreatedAtDesc(
            UUID companyId, UUID storeId, UUID installationId, Pageable pageable);

    long countByCompanyIdAndInstallationIdAndStatusIn(
            UUID companyId, UUID installationId,
            Collection<FiscalIntegrityJobStatus> statuses);

    List<FiscalIntegrityJob> findTop100ByStatusOrderByCreatedAtAsc(FiscalIntegrityJobStatus status);

    List<FiscalIntegrityJob> findTop100ByStatusAndUpdatedAtBeforeOrderByCreatedAtAsc(
            FiscalIntegrityJobStatus status, Instant updatedAt);

    @Query("select j.id from FiscalIntegrityJob j where j.status = "
            + "com.tpverp.backend.verifactu.FiscalIntegrityJobStatus.RUNNING "
            + "and j.updatedAt < :staleBefore order by j.createdAt asc")
    List<UUID> findStaleRunningIds(@org.springframework.data.repository.query.Param("staleBefore")
            Instant staleBefore);

    @Modifying
    @Transactional
    @Query("update FiscalIntegrityJob j set j.status = com.tpverp.backend.verifactu.FiscalIntegrityJobStatus.RUNNING, j.executionToken = :executionToken, j.startedAt = coalesce(j.startedAt, :now), j.updatedAt = :now, j.version = j.version + 1 where j.id = :id and j.status = com.tpverp.backend.verifactu.FiscalIntegrityJobStatus.QUEUED and j.executionToken is null")
    int claimQueued(UUID id, UUID executionToken, Instant now);

    @Modifying
    @Transactional
    @Query(value = "update trabajo_integridad_fiscal set estado = 'QUEUED', token_ejecucion = null, facturacion_comprobada = 0, eventos_comprobados = 0, anomalias_total = 0, anomalias_facturacion = 0, anomalias_eventos = 0, evidencia_codigos = '[]'::jsonb, error = null, iniciado_en = null, completado_en = null, actualizado_en = :now, version = version + 1 where estado = 'RUNNING' and actualizado_en < :staleBefore", nativeQuery = true)
    int requeueStaleJobs(Instant now, Instant staleBefore);

    @Modifying
    @Transactional
    @Query(value = "update trabajo_integridad_fiscal set estado = 'QUEUED', token_ejecucion = null, facturacion_comprobada = 0, eventos_comprobados = 0, anomalias_total = 0, anomalias_facturacion = 0, anomalias_eventos = 0, evidencia_codigos = '[]'::jsonb, error = null, iniciado_en = null, completado_en = null, actualizado_en = :now, version = version + 1 where id = :id and estado = 'RUNNING' and actualizado_en < :staleBefore", nativeQuery = true)
    int requeueRunningJob(UUID id, Instant now, Instant staleBefore);

    @Modifying
    @Transactional
    @Query(value = "update trabajo_integridad_fiscal set facturacion_comprobada = :billingChecked, eventos_comprobados = :eventsChecked, anomalias_total = :anomaliesTotal, anomalias_facturacion = :billingAnomalies, anomalias_eventos = :eventAnomalies, evidencia_codigos = cast(:evidenceCodes as jsonb), actualizado_en = :now, version = version + 1 where id = :id and estado = 'RUNNING' and token_ejecucion = :executionToken", nativeQuery = true)
    int updateProgress(UUID id, UUID executionToken, long billingChecked, long eventsChecked, long anomaliesTotal,
            long billingAnomalies, long eventAnomalies, String evidenceCodes, Instant now);

    @Modifying
    @Transactional
    @Query(value = "update trabajo_integridad_fiscal set estado = 'COMPLETED', token_ejecucion = null, facturacion_comprobada = :billingChecked, eventos_comprobados = :eventsChecked, anomalias_total = :anomaliesTotal, anomalias_facturacion = :billingAnomalies, anomalias_eventos = :eventAnomalies, evidencia_codigos = cast(:evidenceCodes as jsonb), completado_en = :now, actualizado_en = :now, error = null, version = version + 1 where id = :id and estado = 'RUNNING' and token_ejecucion = :executionToken", nativeQuery = true)
    int markCompleted(UUID id, UUID executionToken, long billingChecked, long eventsChecked, long anomaliesTotal,
            long billingAnomalies, long eventAnomalies, String evidenceCodes, Instant now);

    @Modifying
    @Transactional
    @Query("update FiscalIntegrityJob j set j.status = com.tpverp.backend.verifactu.FiscalIntegrityJobStatus.FAILED, j.executionToken = null, j.error = :error, j.updatedAt = :now, j.version = j.version + 1 where j.id = :id and j.status = com.tpverp.backend.verifactu.FiscalIntegrityJobStatus.RUNNING and j.executionToken = :executionToken")
    int markFailedIfRunning(UUID id, UUID executionToken, String error, Instant now);
}
