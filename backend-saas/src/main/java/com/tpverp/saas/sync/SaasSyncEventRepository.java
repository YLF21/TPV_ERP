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
            select event from SaasSyncEvent event
             where (:entityType is null or event.entityType = :entityType)
               and (:companyId is null or event.company.id = :companyId)
               and (:storeId is null or event.store.id = :storeId)
             order by event.receivedAt desc, event.eventId desc
            """)
    List<SaasSyncEvent> findFirstPage(
            @Param("entityType") String entityType,
            @Param("companyId") UUID companyId,
            @Param("storeId") UUID storeId,
            Pageable pageable);

    @Query("""
            select event from SaasSyncEvent event
             where (:entityType is null or event.entityType = :entityType)
               and (:companyId is null or event.company.id = :companyId)
               and (:storeId is null or event.store.id = :storeId)
               and (event.receivedAt < :cursorReceivedAt
                    or (event.receivedAt = :cursorReceivedAt and event.eventId < :cursorEventId))
             order by event.receivedAt desc, event.eventId desc
            """)
    List<SaasSyncEvent> findPageAfter(
            @Param("entityType") String entityType,
            @Param("companyId") UUID companyId,
            @Param("storeId") UUID storeId,
            @Param("cursorReceivedAt") Instant cursorReceivedAt,
            @Param("cursorEventId") UUID cursorEventId,
            Pageable pageable);

    @Query(value = """
            select count(*) as document_count,
                   coalesce(sum(case
                       when operation <> 'ANULAR'
                        and ((payload::jsonb ->> 'tipo') = 'TICKET'
                             or (payload::jsonb ->> 'tipo') like '%_VENTA')
                       then (payload::jsonb ->> 'total')::numeric
                       else 0 end), 0) as total
              from saas_sync_event
             where entity_type = 'DOCUMENTO'
               and (:companyId is null or company_id = :companyId)
               and (:storeId is null or store_id = :storeId)
               and operation <> 'ANULAR'
               and ((payload::jsonb ->> 'tipo') = 'TICKET'
                    or (payload::jsonb ->> 'tipo') like '%_VENTA')
            """, nativeQuery = true)
    SalesSummaryRow aggregateSales(
            @Param("companyId") UUID companyId,
            @Param("storeId") UUID storeId);

    @Query(value = """
            select company_id as company_id, store_id as store_id,
                   payload::jsonb ->> 'productoId' as product_id,
                   payload::jsonb ->> 'almacenId' as warehouse_id,
                   coalesce(sum((payload::jsonb ->> 'cantidad')::numeric), 0) as quantity
              from saas_sync_event
             where entity_type = 'STOCK_MOVEMENT'
               and (:companyId is null or company_id = :companyId)
               and (:storeId is null or store_id = :storeId)
               and (cast(:cursorCompanyId as uuid) is null
                    or company_id > cast(:cursorCompanyId as uuid)
                    or (company_id = cast(:cursorCompanyId as uuid)
                        and coalesce(store_id, '00000000-0000-0000-0000-000000000000'::uuid)
                            > coalesce(cast(:cursorStoreId as uuid), '00000000-0000-0000-0000-000000000000'::uuid))
                    or (company_id = cast(:cursorCompanyId as uuid)
                        and coalesce(store_id, '00000000-0000-0000-0000-000000000000'::uuid)
                            = coalesce(cast(:cursorStoreId as uuid), '00000000-0000-0000-0000-000000000000'::uuid)
                        and (payload::jsonb ->> 'productoId') > cast(:cursorProductId as text))
                    or (company_id = cast(:cursorCompanyId as uuid)
                        and coalesce(store_id, '00000000-0000-0000-0000-000000000000'::uuid)
                            = coalesce(cast(:cursorStoreId as uuid), '00000000-0000-0000-0000-000000000000'::uuid)
                        and (payload::jsonb ->> 'productoId') = cast(:cursorProductId as text)
                        and (payload::jsonb ->> 'almacenId') > cast(:cursorWarehouseId as text)))
             group by company_id, store_id, payload::jsonb ->> 'productoId', payload::jsonb ->> 'almacenId'
             order by company_id asc, store_id asc nulls first, product_id asc, warehouse_id asc
             limit :limit
            """, nativeQuery = true)
    List<StockSnapshotRow> aggregateStock(
            @Param("companyId") UUID companyId,
            @Param("storeId") UUID storeId,
            @Param("cursorCompanyId") UUID cursorCompanyId,
            @Param("cursorStoreId") UUID cursorStoreId,
            @Param("cursorProductId") String cursorProductId,
            @Param("cursorWarehouseId") String cursorWarehouseId,
            @Param("limit") int limit);

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

    interface SalesSummaryRow {
        long getDocumentCount();
        java.math.BigDecimal getTotal();
    }

    interface StockSnapshotRow {
        UUID getCompanyId();
        UUID getStoreId();
        String getProductId();
        String getWarehouseId();
        java.math.BigDecimal getQuantity();
    }
}
