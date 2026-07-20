package com.tpverp.backend.document;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerReceivablePaymentReservationRepository
        extends JpaRepository<CustomerReceivablePaymentReservation, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select reservation from CustomerReceivablePaymentReservation reservation where reservation.id=:id")
    Optional<CustomerReceivablePaymentReservation> findLockedById(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select reservation from CustomerReceivablePaymentReservation reservation
            where reservation.documentId=:documentId
            """)
    List<CustomerReceivablePaymentReservation> findAllLockedByDocumentId(
            @Param("documentId") UUID documentId);
}
