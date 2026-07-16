package com.tpverp.backend.document;

import java.time.LocalDate;
import java.util.UUID;

public record CustomerReceivableFilter(
        UUID customerId,
        String search,
        DocumentStatus status,
        Boolean overdue,
        CommercialDocumentType documentType,
        LocalDate dueFrom,
        LocalDate dueTo) {
}
