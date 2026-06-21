package com.tpverp.backend.verifactu;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FiscalRecordRepository extends JpaRepository<FiscalRecord, UUID> {

    List<FiscalRecord> findAllByChainIdOrderBySequence(UUID chainId);

    Optional<FiscalRecord> findByDocumentIdAndOperation(
            UUID documentId, FiscalRecordOperation operation);

    Optional<FiscalRecord> findByIdAndCompanyIdAndStoreId(
            UUID id, UUID companyId, UUID storeId);
}
