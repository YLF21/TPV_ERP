package com.tpverp.backend.sync;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncInboxEventRepository extends JpaRepository<SyncInboxEvent, UUID> {

    Optional<SyncInboxEvent> findByEventId(UUID eventId);
}
