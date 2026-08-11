package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundTenderRepository extends JpaRepository<RefundTender, UUID> {

    @EntityGraph(attributePaths = "refundDocument")
    List<RefundTender> findByRefundDocumentIdOrderByCreatedAtAsc(UUID refundDocumentId);

    @Query("""
            select tender
            from RefundTender tender
            join fetch tender.refundDocument document
            where document.tiendaId = :storeId
              and tender.createdAt >= :from
              and tender.createdAt < :to
            order by tender.createdAt asc, tender.id asc
            """)
    List<RefundTender> findAllByStoreAndCreatedBetween(
            @Param("storeId") UUID storeId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Query("""
            select tender.refundDocument.id as refundDocumentId,
                   sum(tender.amount) as totalAmount
            from RefundTender tender
            where tender.refundDocument.tiendaId = :storeId
              and tender.refundDocument.id in :documentIds
            group by tender.refundDocument.id
            """)
    List<RefundDocumentTotal> sumByRefundDocumentIds(
            @Param("storeId") UUID storeId,
            @Param("documentIds") Collection<UUID> documentIds);

    @Query("""
            select coalesce(sum(tender.amount), 0)
              from RefundTender tender
             where tender.originalPaymentId = :originalPaymentId
            """)
    BigDecimal refundedAmountByOriginalPaymentId(
            @Param("originalPaymentId") UUID originalPaymentId);

    interface RefundDocumentTotal {
        UUID getRefundDocumentId();

        BigDecimal getTotalAmount();
    }
}
