package com.tpverp.backend.document;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CustomerReceivablePaymentHistoryFilter(
        LocalDate collectedFrom,
        LocalDate collectedTo,
        String search,
        UUID paymentMethodId,
        UUID customerId,
        List<UUID> paymentMethodIds) {
    public CustomerReceivablePaymentHistoryFilter(LocalDate collectedFrom, LocalDate collectedTo, String search,
            UUID paymentMethodId, UUID customerId) {
        this(collectedFrom, collectedTo, search, paymentMethodId, customerId, null);
    }

}
