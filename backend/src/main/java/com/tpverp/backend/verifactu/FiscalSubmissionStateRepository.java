package com.tpverp.backend.verifactu;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FiscalSubmissionStateRepository
        extends JpaRepository<FiscalSubmissionState, UUID> {

    List<FiscalSubmissionState> findAllByStatusInOrderByUpdatedAtDesc(
            Collection<FiscalSubmissionStatus> statuses);

    List<FiscalSubmissionState> findAllByStatusInOrderByUpdatedAtAsc(
            Collection<FiscalSubmissionStatus> statuses);
}
