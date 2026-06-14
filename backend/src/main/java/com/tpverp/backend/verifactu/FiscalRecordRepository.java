package com.tpverp.backend.verifactu;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FiscalRecordRepository extends JpaRepository<FiscalRecord, UUID> {

    List<FiscalRecord> findAllByChainIdOrderBySequence(UUID chainId);
}
