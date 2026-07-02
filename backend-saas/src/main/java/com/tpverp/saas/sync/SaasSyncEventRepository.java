package com.tpverp.saas.sync;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaasSyncEventRepository extends JpaRepository<SaasSyncEvent, UUID> {

    List<SaasSyncEvent> findTop200ByOrderByReceivedAtDesc();

    List<SaasSyncEvent> findTop200ByEntityTypeOrderByReceivedAtDesc(String entityType);

    List<SaasSyncEvent> findByEntityTypeOrderByReceivedAtAsc(String entityType);
}
