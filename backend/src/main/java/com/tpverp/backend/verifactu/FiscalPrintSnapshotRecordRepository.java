package com.tpverp.backend.verifactu;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FiscalPrintSnapshotRecordRepository
        extends JpaRepository<FiscalPrintSnapshotRecord, UUID> {
    Optional<FiscalPrintSnapshotRecord> findByRecordId(UUID recordId);
}
