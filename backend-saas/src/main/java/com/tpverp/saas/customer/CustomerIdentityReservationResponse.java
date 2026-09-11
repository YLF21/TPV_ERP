package com.tpverp.saas.customer;

import java.util.UUID;

public record CustomerIdentityReservationResponse(
        UUID operationId, UUID customerId, long revision, String documentType, String documentNumber) {
}
