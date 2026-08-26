package com.tpverp.backend.party.loyalty.central;

import java.util.UUID;

public class MemberBalanceManualReconciliationRequiredException extends RuntimeException {

    private final UUID reservationId;
    private final LocalMemberBalanceReservationStatus reservationStatus;

    public MemberBalanceManualReconciliationRequiredException(
            UUID reservationId,
            LocalMemberBalanceReservationStatus reservationStatus) {
        super("La reserva de saldo socio " + reservationId
                + " esta en estado " + reservationStatus
                + " y requiere conciliacion manual");
        this.reservationId = reservationId;
        this.reservationStatus = reservationStatus;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public LocalMemberBalanceReservationStatus getReservationStatus() {
        return reservationStatus;
    }
}
