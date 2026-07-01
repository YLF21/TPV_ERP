package com.tpverp.backend.sync;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncOutboxEventRepository extends JpaRepository<SyncOutboxEvent, UUID> {

    List<SyncOutboxEvent> findByStatusOrderByCreatedAtAsc(SyncOutboxStatus status);

    long countByStatus(SyncOutboxStatus status);
}
