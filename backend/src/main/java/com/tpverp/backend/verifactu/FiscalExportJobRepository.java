package com.tpverp.backend.verifactu;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Collection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface FiscalExportJobRepository extends JpaRepository<FiscalExportJob, UUID> {
    Optional<FiscalExportJob> findByIdAndCompanyIdAndStoreIdAndInstallationId(
            UUID id, UUID companyId, UUID storeId, UUID installationId);

    Page<FiscalExportJob> findAllByCompanyIdAndStoreIdAndInstallationIdAndRequestedByOrderByCreatedAtDesc(
            UUID companyId, UUID storeId, UUID installationId, String requestedBy, Pageable pageable);

    Page<FiscalExportJob> findAllByCompanyIdAndStoreIdAndInstallationIdOrderByCreatedAtDesc(
            UUID companyId, UUID storeId, UUID installationId, Pageable pageable);

    long countByCompanyIdAndStoreIdAndInstallationIdAndRequestedByAndScopeAndStatusIn(
            UUID companyId, UUID storeId, UUID installationId, String requestedBy,
            FiscalExportJobScope scope, Collection<FiscalExportJobStatus> statuses);

    List<FiscalExportJob> findTop100ByStatusOrderByCreatedAtAsc(FiscalExportJobStatus status);

    List<FiscalExportJob> findTop100ByStatusAndUpdatedAtBeforeOrderByCreatedAtAsc(
            FiscalExportJobStatus status, Instant updatedAt);

    List<FiscalExportJob> findTop100ByExpiresAtBeforeAndStatusInOrderByExpiresAtAsc(
            Instant expiresAt, Collection<FiscalExportJobStatus> statuses);

    @Modifying
    @Transactional
    @Query("update FiscalExportJob j set j.status = com.tpverp.backend.verifactu.FiscalExportJobStatus.RUNNING, j.executionToken = :executionToken, j.startedAt = coalesce(j.startedAt, :now), j.updatedAt = :now, j.version = j.version + 1 where j.id = :id and j.status = com.tpverp.backend.verifactu.FiscalExportJobStatus.QUEUED and j.executionToken is null and j.expiresAt > :now")
    int claimQueued(UUID id, UUID executionToken, Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from FiscalExportJob j where j.id = :id and j.executionToken = :executionToken and j.status = com.tpverp.backend.verifactu.FiscalExportJobStatus.RUNNING")
    Optional<FiscalExportJob> findForUpdateByIdAndExecutionToken(UUID id, UUID executionToken);

    @Query("select j from FiscalExportJob j where j.id = :id and j.executionToken = :executionToken and j.status = com.tpverp.backend.verifactu.FiscalExportJobStatus.RUNNING")
    Optional<FiscalExportJob> findByIdAndExecutionToken(UUID id, UUID executionToken);

    @Modifying
    @Transactional
    @Query("update FiscalExportJob j set j.status = com.tpverp.backend.verifactu.FiscalExportJobStatus.FAILED, j.error = :error, j.updatedAt = :now, j.version = j.version + 1 where j.id = :id and j.status = com.tpverp.backend.verifactu.FiscalExportJobStatus.QUEUED")
    int failQueued(UUID id, String error, Instant now);

    @Modifying
    @Transactional
    @Query("update FiscalExportJob j set j.status = com.tpverp.backend.verifactu.FiscalExportJobStatus.EXPIRED, j.executionToken = null, j.updatedAt = :now, j.version = j.version + 1 where j.id = :id and j.status in (com.tpverp.backend.verifactu.FiscalExportJobStatus.QUEUED, com.tpverp.backend.verifactu.FiscalExportJobStatus.FAILED, com.tpverp.backend.verifactu.FiscalExportJobStatus.COMPLETED) and j.expiresAt < :now")
    int expireIfEligible(UUID id, Instant now);

    @Modifying
    @Transactional
    @Query("update FiscalExportJob j set j.status = com.tpverp.backend.verifactu.FiscalExportJobStatus.QUEUED, j.executionToken = null, j.processed = 0, j.hasMore = false, j.error = null, j.filePath = null, j.fileSize = 0, j.startedAt = null, j.completedAt = null, j.updatedAt = :now, j.version = j.version + 1 where j.status = com.tpverp.backend.verifactu.FiscalExportJobStatus.RUNNING")
    int requeueRunning(Instant now);

    @Modifying
    @Transactional
    @Query("update FiscalExportJob j set j.status = com.tpverp.backend.verifactu.FiscalExportJobStatus.QUEUED, j.executionToken = null, j.processed = 0, j.hasMore = false, j.error = null, j.filePath = null, j.fileSize = 0, j.startedAt = null, j.completedAt = null, j.updatedAt = :now, j.version = j.version + 1 where j.id = :id and j.status = com.tpverp.backend.verifactu.FiscalExportJobStatus.RUNNING and j.updatedAt < :staleBefore")
    int requeueRunningJob(UUID id, Instant now, Instant staleBefore);

    @Modifying
    @Transactional
    @Query("update FiscalExportJob j set j.processed = :processed, j.hasMore = :hasMore, j.updatedAt = :now where j.id = :id and j.status = com.tpverp.backend.verifactu.FiscalExportJobStatus.RUNNING and j.executionToken = :executionToken")
    int updateProgress(UUID id, UUID executionToken, long processed, boolean hasMore, Instant now);

    @Modifying
    @Transactional
    @Query("update FiscalExportJob j set j.status = com.tpverp.backend.verifactu.FiscalExportJobStatus.FAILED, j.executionToken = null, j.error = :error, j.updatedAt = :now where j.id = :id and j.status = com.tpverp.backend.verifactu.FiscalExportJobStatus.RUNNING and j.executionToken = :executionToken")
    int markFailedIfRunning(UUID id, UUID executionToken, String error, Instant now);
}
