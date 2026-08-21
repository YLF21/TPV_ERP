package com.tpverp.backend.party.loyalty.central;

public enum LocalMemberBalanceReservationStatus {
    ACTIVE,
    PREPARED,
    TICKET_COMMITTED,
    FINALIZE_PENDING,
    ABORT_PENDING,
    RELEASE_PENDING,
    RELEASED,
    EXPIRED,
    CONSUMED
}
