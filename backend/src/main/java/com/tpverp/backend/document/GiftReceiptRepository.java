package com.tpverp.backend.document;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GiftReceiptRepository extends JpaRepository<GiftReceipt, UUID> {

    @EntityGraph(attributePaths = {"lines", "lines.serialNumbers"})
    Optional<GiftReceipt> findByStoreIdAndCodigoIgnoreCase(UUID storeId, String code);

    @EntityGraph(attributePaths = {"lines", "lines.serialNumbers"})
    Optional<GiftReceipt> findByStoreIdAndRequestId(UUID storeId, UUID requestId);
}
