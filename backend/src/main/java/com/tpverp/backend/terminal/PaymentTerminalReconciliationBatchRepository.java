package com.tpverp.backend.terminal;
import java.util.Optional; import java.util.UUID; import org.springframework.data.jpa.repository.JpaRepository;
public interface PaymentTerminalReconciliationBatchRepository extends JpaRepository<PaymentTerminalReconciliationBatch,UUID>{
    Optional<PaymentTerminalReconciliationBatch> findByIdAndStoreIdAndCompanyId(UUID id,UUID storeId,UUID companyId);
}
