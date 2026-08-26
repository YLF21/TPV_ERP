package com.tpverp.saas.sync;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

public interface SaasSyncEventRepository extends JpaRepository<SaasSyncEvent, UUID> {

    @Query("""
            select event
              from SaasSyncEvent event
             where (:entityType is null or event.entityType = :entityType)
               and (:companyId is null or event.company.id = :companyId)
               and (:storeId is null or event.store.id = :storeId)
             order by event.receivedAt desc
            """)
    List<SaasSyncEvent> findRecent(
            @Param("entityType") String entityType,
            @Param("companyId") UUID companyId,
            @Param("storeId") UUID storeId,
            Pageable pageable);

    @Query("""
            select event
              from SaasSyncEvent event
             where event.entityType = :entityType
               and (:companyId is null or event.company.id = :companyId)
               and (:storeId is null or event.store.id = :storeId)
             order by event.receivedAt asc
            """)
    List<SaasSyncEvent> findChronological(
            @Param("entityType") String entityType,
            @Param("companyId") UUID companyId,
            @Param("storeId") UUID storeId);

    @Query("""
            select event.projectionStatus as status,
                   count(event) as total,
                   min(event.receivedAt) as oldestReceivedAt
              from SaasSyncEvent event
             where (:companyId is null or event.company.id = :companyId)
               and (:storeId is null or event.store.id = :storeId)
             group by event.projectionStatus
            """)
    List<ProjectionStatusCount> countProjectionStatuses(
            @Param("companyId") UUID companyId,
            @Param("storeId") UUID storeId);

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

    interface ProjectionStatusCount {
        SaasSyncEvent.ProjectionStatus getStatus();
        long getTotal();
        Instant getOldestReceivedAt();
    }
}
