package com.tpverp.backend.document;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoucherEventRepository extends JpaRepository<VoucherEvent, UUID> {

    boolean existsByVoucher_IdAndDocumentIdAndType(
            UUID voucherId, UUID documentId, VoucherEventType type);

    List<VoucherEvent> findAllByDocumentIdOrderByOccurredAtAsc(UUID documentId);
}
