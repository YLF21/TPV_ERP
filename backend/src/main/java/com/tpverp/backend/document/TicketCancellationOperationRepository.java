package com.tpverp.backend.document;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TicketCancellationOperationRepository
        extends JpaRepository<TicketCancellationOperation, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select operation
              from TicketCancellationOperation operation
             where operation.id = :id
            """)
    Optional<TicketCancellationOperation> findLockedById(@Param("id") UUID id);

    @Query("""
            select operation
              from TicketCancellationOperation operation
             where operation.ticketId = :ticketId
               and operation.status in (
                   com.tpverp.backend.document.TicketCancellationStatus.PREPARED,
                   com.tpverp.backend.document.TicketCancellationStatus.COMPENSATING,
                   com.tpverp.backend.document.TicketCancellationStatus.READY,
                   com.tpverp.backend.document.TicketCancellationStatus.REVIEW_REQUIRED)
            """)
    Optional<TicketCancellationOperation> findActiveByTicketId(
            @Param("ticketId") UUID ticketId);

    default boolean hasActiveCancellation(UUID ticketId) {
        return findActiveByTicketId(ticketId).isPresent();
    }
}
