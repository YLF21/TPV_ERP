package com.tpverp.saas.loyalty;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A real lease collision, safe for the local POS to classify as a duplicate reservation. */
public final class MemberBalanceReservationConflictException extends ResponseStatusException {

    public MemberBalanceReservationConflictException(String reason) {
        super(HttpStatus.CONFLICT, reason);
    }
}
