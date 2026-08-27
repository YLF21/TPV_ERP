package com.tpverp.backend.verifactu;

import java.util.Optional;
import java.util.UUID;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FiscalPrintSnapshotRecordRepository
        extends JpaRepository<FiscalPrintSnapshotRecord, UUID> {
    Optional<FiscalPrintSnapshotRecord> findByRecordId(UUID recordId);

    /** Bounded batch read for integrity checks and defect views. */
    List<FiscalPrintSnapshotRecord> findAllByRecordIdIn(Collection<UUID> recordIds);
}
