package com.tpverp.backend.sync;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sync")
public class SyncController {

    private final SyncInboxService service;
    private final SyncOutboxStatusService outboxStatus;
    private final SyncOutboxWorker outboxWorker;

    public SyncController(
            SyncInboxService service,
            SyncOutboxStatusService outboxStatus,
            SyncOutboxWorker outboxWorker) {
        this.service = service;
        this.outboxStatus = outboxStatus;
        this.outboxWorker = outboxWorker;
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
}
