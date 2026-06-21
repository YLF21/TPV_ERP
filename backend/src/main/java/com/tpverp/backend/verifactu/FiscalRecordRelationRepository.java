package com.tpverp.backend.verifactu;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FiscalRecordRelationRepository
        extends JpaRepository<FiscalRecordRelation, FiscalRecordRelation.Key> {

    Optional<FiscalRecordRelation> findByRecordIdAndType(
            UUID recordId, FiscalRelationType type);
}
