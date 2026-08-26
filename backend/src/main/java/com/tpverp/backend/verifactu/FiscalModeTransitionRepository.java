package com.tpverp.backend.verifactu;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FiscalModeTransitionRepository extends JpaRepository<FiscalModeTransition, UUID> {
    List<FiscalModeTransition> findTop50ByCompanyIdOrderByEffectiveAtDesc(UUID companyId);

    Optional<FiscalModeTransition> findTopByCompanyIdAndInstallationIdAndStatusOrderByRequestedAtDesc(
            UUID companyId, UUID installationId, FiscalModeTransitionStatus status);

    Optional<FiscalModeTransition> findTopByCompanyIdAndInstallationIdAndStatusInOrderByRequestedAtDesc(
            UUID companyId, UUID installationId,
            java.util.Collection<FiscalModeTransitionStatus> statuses);

    @Query("select count(applied.id) from FiscalModeTransition applied "
            + "where applied.companyId = :companyId "
            + "and applied.installationId = :installationId "
            + "and applied.status = :appliedStatus "
            + "and applied.previousMode = :previousMode "
            + "and applied.newMode = :newMode "
            + "and applied.expectedVersion = :expectedVersion "
            + "and applied.effectiveAt >= :scheduledEffectiveAt")
    long countAppliedTransitionsForSchedule(
            @Param("companyId") UUID companyId,
            @Param("installationId") UUID installationId,
            @Param("appliedStatus") FiscalModeTransitionStatus appliedStatus,
            @Param("previousMode") FiscalMode previousMode,
            @Param("newMode") FiscalMode newMode,
            @Param("expectedVersion") long expectedVersion,
            @Param("scheduledEffectiveAt") java.time.Instant scheduledEffectiveAt);

    @Query("select scheduled from FiscalModeTransition scheduled "
            + "where scheduled.status = :scheduledStatus "
            + "and scheduled.effectiveAt <= :effectiveAt "
            + "and not exists (select failure.id from FiscalModeTransition failure "
            + "where failure.sourceTransitionId = scheduled.id "
            + "and failure.status = :failedStatus) "
            + "and not exists (select applied.id from FiscalModeTransition applied "
            + "where applied.companyId = scheduled.companyId "
            + "and applied.installationId = scheduled.installationId "
            + "and applied.status = :appliedStatus "
            + "and applied.previousMode = com.tpverp.backend.verifactu.FiscalMode.VERIFACTU "
            + "and applied.newMode = com.tpverp.backend.verifactu.FiscalMode.NO_VERIFACTU "
            + "and applied.requestedAt >= scheduled.requestedAt) "
            + "order by scheduled.effectiveAt asc")
    List<FiscalModeTransition> findDueWithoutAppliedTransition(
            @Param("scheduledStatus") FiscalModeTransitionStatus scheduledStatus,
            @Param("appliedStatus") FiscalModeTransitionStatus appliedStatus,
            @Param("failedStatus") FiscalModeTransitionStatus failedStatus,
            @Param("effectiveAt") java.time.Instant effectiveAt);
}
