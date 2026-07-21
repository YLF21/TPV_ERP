package com.tpverp.backend.document;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundTenderRepository extends JpaRepository<RefundTender, UUID> {

    @EntityGraph(attributePaths = "refundDocument")
    List<RefundTender> findByRefundDocumentIdOrderByCreatedAtAsc(UUID refundDocumentId);
}
