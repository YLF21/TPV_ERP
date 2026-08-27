package com.tpverp.backend.sync;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sync")
public class SyncController {

    private final SyncInboxService service;
    private final SyncOutboxStatusService outboxStatus;
    private final SyncOutboxWorker outboxWorker;
    private final SyncOutboxIncidentService incidents;

    public SyncController(
            SyncInboxService service,
            SyncOutboxStatusService outboxStatus,
            SyncOutboxWorker outboxWorker,
            SyncOutboxIncidentService incidents) {
        this.service = service;
        this.outboxStatus = outboxStatus;
        this.outboxWorker = outboxWorker;
        this.incidents = incidents;
    }

    @PostMapping("/events")
    @PreAuthorize("hasRole('ADMIN')")
    public SyncInboxReceipt receive(@Valid @RequestBody SyncInboundEventRequest request) {
        return service.receive(request);
    }

    @GetMapping("/outbox/status")
    @PreAuthorize("hasRole('ADMIN')")
    public SyncOutboxStatusView outboxStatus() {
        return outboxStatus.status();
    }

    @PostMapping("/outbox/flush")
    @PreAuthorize("hasRole('ADMIN')")
    public SyncOutboxFlushResponse flushOutbox() {
        return new SyncOutboxFlushResponse(outboxWorker.runOnce());
    }

    @GetMapping("/outbox/incidents")
    @PreAuthorize("hasRole('ADMIN')")
    public List<SyncOutboxIncidentView> incidents() {
        return incidents.listDeadLetters();
    }

    @PostMapping("/outbox/events/{eventId}/retry")
    @PreAuthorize("hasRole('ADMIN')")
    public SyncOutboxIncidentView retry(
            @PathVariable UUID eventId,
            @Valid @RequestBody ManualRetryRequest request) {
        return incidents.retry(eventId, request.expectedVersion(), request.reason());
    }

    public record ManualRetryRequest(
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotBlank @Size(max = 500) String reason) {
    }
}
