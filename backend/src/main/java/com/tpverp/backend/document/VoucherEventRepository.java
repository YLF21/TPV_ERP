package com.tpverp.backend.document;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VoucherEventRepository extends JpaRepository<VoucherEvent, UUID> {

    boolean existsByVoucher_IdAndDocumentIdAndType(
            UUID voucherId, UUID documentId, VoucherEventType type);

    @Query("""
            select event
            from VoucherEvent event
            join fetch event.voucher
            where event.documentId = :documentId
            order by event.occurredAt asc
            """)
    List<VoucherEvent> findAllByDocumentIdOrderByOccurredAtAsc(
            @Param("documentId") UUID documentId);
}
