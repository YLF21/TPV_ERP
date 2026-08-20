package com.tpverp.saas.loyalty;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaasMemberBalanceReservationLotRepository
        extends JpaRepository<SaasMemberBalanceReservationLot, UUID> {

    List<SaasMemberBalanceReservationLot> findByReservation_IdOrderByLot_CreatedAtAscLot_IdAsc(
            UUID reservationId);
}

