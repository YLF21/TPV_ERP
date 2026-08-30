package com.tpverp.backend.party.loyalty.central;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LocalMemberBalanceReservationRepository
        extends JpaRepository<LocalMemberBalanceReservation, UUID> {

    Optional<LocalMemberBalanceReservation> findByCentralReservationId(UUID centralReservationId);

    Optional<LocalMemberBalanceReservation> findFirstByStoreIdAndTerminalIdAndSaleIdOrderByCreatedAtDesc(
            UUID storeId,
            UUID terminalId,
            String saleId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select reservation
            from LocalMemberBalanceReservation reservation
            where reservation.storeId = :storeId
              and reservation.terminalId = :terminalId
              and reservation.memberId = :memberId
              and reservation.status in :statuses
            order by reservation.createdAt asc
            """)
    List<LocalMemberBalanceReservation> findForRetry(
            @Param("storeId") UUID storeId,
            @Param("terminalId") UUID terminalId,
            @Param("memberId") UUID memberId,
            @Param("statuses") Collection<LocalMemberBalanceReservationStatus> statuses);

    @Query("""
            select min(reservation.createdAt)
            from LocalMemberBalanceReservation reservation
            where reservation.memberId = :memberId
              and reservation.status in :statuses
              and reservation.leaseExpiresAt > :now
            """)
    Optional<Instant> findExpiryBlockStartedAt(
            @Param("memberId") UUID memberId,
            @Param("statuses") Collection<LocalMemberBalanceReservationStatus> statuses,
            @Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select reservation
            from LocalMemberBalanceReservation reservation
            where reservation.id = :id
            """)
    Optional<LocalMemberBalanceReservation> findForUpdate(@Param("id") UUID id);
}
