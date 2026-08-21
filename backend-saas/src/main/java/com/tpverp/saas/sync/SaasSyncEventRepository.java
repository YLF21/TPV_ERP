package com.tpverp.saas.sync;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SaasSyncEventRepository extends JpaRepository<SaasSyncEvent, UUID> {

    List<SaasSyncEvent> findTop200ByOrderByReceivedAtDesc();

    List<SaasSyncEvent> findTop200ByEntityTypeOrderByReceivedAtDesc(String entityType);

    List<SaasSyncEvent> findByEntityTypeOrderByReceivedAtAsc(String entityType);

    @Modifying
    @Query(value = """
            INSERT INTO saas_sync_event_lock(event_id)
            VALUES (:eventId)
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    void ensureEventLock(@Param("eventId") UUID eventId);

    @Query(value = """
            SELECT event_id
            FROM saas_sync_event_lock
            WHERE event_id = :eventId
            FOR UPDATE
            """, nativeQuery = true)
    UUID lockEvent(@Param("eventId") UUID eventId);
}
