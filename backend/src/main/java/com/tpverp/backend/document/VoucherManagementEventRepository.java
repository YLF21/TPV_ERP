package com.tpverp.backend.document;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoucherManagementEventRepository
        extends JpaRepository<VoucherManagementEvent, UUID> {

    List<VoucherManagementEvent> findAllByVoucher_IdOrderByOccurredAtDesc(UUID voucherId);
}
