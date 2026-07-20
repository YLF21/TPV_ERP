package com.tpverp.backend.document;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class DocumentOperationalEventRecorder {

    private final DocumentOperationalEventRepository events;

    public DocumentOperationalEventRecorder(DocumentOperationalEventRepository events) {
        this.events = events;
    }

    public void record(
            CommercialDocument document,
            DocumentOperationalEventType type,
            UUID userId,
            UUID terminalId,
            Instant occurredAt) {
        record(document, type, userId, terminalId, occurredAt, Map.of());
    }

    public void record(
            CommercialDocument document,
            DocumentOperationalEventType type,
            UUID userId,
            UUID terminalId,
            Instant occurredAt,
            Map<String, Object> data) {
        events.save(new DocumentOperationalEvent(
                document, type, userId, terminalId, occurredAt, data));
    }
}
