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

public interface FiscalSubmissionStateRepository
        extends JpaRepository<FiscalSubmissionState, UUID> {

    List<FiscalSubmissionState> findAllByStatusInOrderByUpdatedAtDesc(
            Collection<FiscalSubmissionStatus> statuses);

    List<FiscalSubmissionState> findAllByStatusInOrderByUpdatedAtAsc(
            Collection<FiscalSubmissionStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select state from FiscalSubmissionState state where state.recordId = :recordId")
    Optional<FiscalSubmissionState> findForUpdate(@Param("recordId") UUID recordId);
}
