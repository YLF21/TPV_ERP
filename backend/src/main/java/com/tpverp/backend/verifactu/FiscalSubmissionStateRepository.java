package com.tpverp.backend.verifactu;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Pageable;

public interface FiscalSubmissionStateRepository
        extends JpaRepository<FiscalSubmissionState, UUID> {

    List<FiscalSubmissionState> findAllByStatusInOrderByUpdatedAtDesc(
            Collection<FiscalSubmissionStatus> statuses);

    List<FiscalSubmissionState> findAllByStatusInOrderByUpdatedAtAsc(
            Collection<FiscalSubmissionStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select state from FiscalSubmissionState state where state.recordId = :recordId")
    Optional<FiscalSubmissionState> findForUpdate(@Param("recordId") UUID recordId);

    @Query("""
            select new com.tpverp.backend.verifactu.VerifactuPosQueueRecord(
                record.number,
                record.documentType,
                state.status,
                state.updatedAt)
            from FiscalSubmissionState state
            join FiscalRecord record on record.id = state.recordId
            join CommercialDocument commercialDocument
                on commercialDocument.id = record.documentId
            where record.companyId = :companyId
              and record.storeId = :storeId
              and commercialDocument.tiendaId = :storeId
              and commercialDocument.terminalOrigenId = :terminalId
              and state.status in :statuses
            order by state.updatedAt desc, record.sequence desc, record.id desc
            """)
    List<VerifactuPosQueueRecord> findPosQueue(
            @Param("companyId") UUID companyId,
            @Param("storeId") UUID storeId,
            @Param("terminalId") UUID terminalId,
            @Param("statuses") Collection<FiscalSubmissionStatus> statuses,
            Pageable pageable);

    @Query("""
            select count(state)
            from FiscalSubmissionState state
            join FiscalRecord record on record.id = state.recordId
            join CommercialDocument commercialDocument
                on commercialDocument.id = record.documentId
            where record.companyId = :companyId
              and record.storeId = :storeId
              and commercialDocument.tiendaId = :storeId
              and commercialDocument.terminalOrigenId = :terminalId
              and state.status in :statuses
            """)
    long countPosQueueByStatusIn(
            @Param("companyId") UUID companyId,
            @Param("storeId") UUID storeId,
            @Param("terminalId") UUID terminalId,
            @Param("statuses") Collection<FiscalSubmissionStatus> statuses);

    @Query("""
            select count(state)
            from FiscalSubmissionState state
            join FiscalRecord record on record.id = state.recordId
            where record.companyId = :companyId
              and state.status in :statuses
            """)
    long countByCompanyIdAndStatusIn(
            @Param("companyId") UUID companyId,
            @Param("statuses") Collection<FiscalSubmissionStatus> statuses);
}
