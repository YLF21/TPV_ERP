package com.tpverp.backend.document;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record DocumentOperationalTimelineView(
        UUID documentId,
        CommercialDocumentType documentType,
        DocumentStatus documentStatus,
        String documentNumber,
        LocalDate documentDate,
        UUID originUserId,
        String originUserName,
        UUID originTerminalId,
        String originTerminalName,
        List<EventView> events) {

    public record EventView(
            UUID id,
            DocumentOperationalEventType type,
            UUID userId,
            String userName,
            UUID terminalId,
            String terminalName,
            Instant occurredAt,
            Map<String, Object> data) {
    }
}
