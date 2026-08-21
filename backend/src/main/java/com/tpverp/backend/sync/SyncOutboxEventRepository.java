package com.tpverp.backend.sync;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SyncOutboxEventRepository extends JpaRepository<SyncOutboxEvent, UUID> {

    List<SyncOutboxEvent> findByStatusOrderByCreatedAtAsc(SyncOutboxStatus status);

    long countByStatus(SyncOutboxStatus status);

    @Query(value = """
            select event.*
              from sync_outbox event
             where (event.estado in ('PENDIENTE', 'ERROR')
                    and event.proximo_intento_en <= :now)
                or (event.estado = 'ENVIANDO'
                    and (event.reclamado_en is null or event.reclamado_en <= :staleBefore))
             order by case when event.estado = 'ENVIANDO' then 0 else 1 end,
                      coalesce(event.proximo_intento_en, event.reclamado_en, event.creado_en),
                      event.creado_en,
                      event.id
             limit :batchSize
             for update skip locked
            """, nativeQuery = true)
    List<SyncOutboxEvent> findClaimableForUpdate(
            @Param("now") Instant now,
            @Param("staleBefore") Instant staleBefore,
            @Param("batchSize") int batchSize);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select event from SyncOutboxEvent event where event.eventId = :eventId")
    Optional<SyncOutboxEvent> findLockedByEventId(@Param("eventId") UUID eventId);
}
