package com.tpverp.saas.loyalty;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaasMemberBalanceRetentionReceiptRepository
        extends JpaRepository<SaasMemberBalanceRetentionReceipt, UUID> {

    Optional<SaasMemberBalanceRetentionReceipt> findByOperationId(UUID operationId);

    Optional<SaasMemberBalanceRetentionReceipt> findByCompanyIdAndReturnDocumentId(
            UUID companyId, UUID returnDocumentId);
}
