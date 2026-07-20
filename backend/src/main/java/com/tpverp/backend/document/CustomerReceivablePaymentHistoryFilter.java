package com.tpverp.backend.document;

import java.time.LocalDate;
import java.util.UUID;

public record CustomerReceivablePaymentHistoryFilter(
        LocalDate collectedFrom,
        LocalDate collectedTo,
        String search,
        UUID paymentMethodId,
        UUID customerId) {
}
