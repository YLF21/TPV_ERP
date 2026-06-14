package com.tpverp.backend.verifactu;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FiscalRecordRelationRepository
        extends JpaRepository<FiscalRecordRelation, FiscalRecordRelation.Key> {
}
