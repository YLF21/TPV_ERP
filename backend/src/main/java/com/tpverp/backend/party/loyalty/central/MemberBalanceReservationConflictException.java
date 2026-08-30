package com.tpverp.backend.party.loyalty.central;

/** Conflict returned by the initial reservation endpoint, distinct from lease/prepare conflicts. */
public final class MemberBalanceReservationConflictException extends MemberBalanceCentralException {

    public MemberBalanceReservationConflictException(String message, Throwable cause) {
        super(Kind.CONFLICT, message, cause);
    }

    public MemberBalanceReservationConflictException(
            String message,
            Integer statusCode,
            Throwable cause) {
        super(Kind.CONFLICT, statusCode, message, cause);
    }
}
