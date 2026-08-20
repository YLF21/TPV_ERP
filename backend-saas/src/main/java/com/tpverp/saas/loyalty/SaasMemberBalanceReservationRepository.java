package com.tpverp.saas.loyalty;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;

public interface SaasMemberBalanceReservationRepository
        extends JpaRepository<SaasMemberBalanceReservation, UUID> {

    Optional<SaasMemberBalanceReservation> findFirstByAccount_IdAndStatusInOrderByCreatedAtDesc(
            UUID accountId,
            Collection<String> statuses);

    @Query("""
            select count(reservation)
            from SaasMemberBalanceReservation reservation
            where reservation.account.companyId = :companyId
              and (reservation.status = 'PREPARED'
                   or (reservation.status = 'ACTIVE' and reservation.leaseExpiresAt > :now))
            """)
    long countLiveByCompany(
            @Param("companyId") UUID companyId,
            @Param("now") Instant now);
}
