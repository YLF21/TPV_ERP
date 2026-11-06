package com.tpverp.backend.document;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CustomerReceivableFilter(
        UUID customerId,
        String search,
        DocumentStatus status,
        Boolean overdue,
        CommercialDocumentType documentType,
        LocalDate dueFrom,
        LocalDate dueTo,
        List<DocumentStatus> statuses,
        List<CommercialDocumentType> documentTypes) {
    public CustomerReceivableFilter(UUID customerId, String search, DocumentStatus status, Boolean overdue,
            CommercialDocumentType documentType, LocalDate dueFrom, LocalDate dueTo) {
        this(customerId, search, status, overdue, documentType, dueFrom, dueTo, null, null);
    }

}
