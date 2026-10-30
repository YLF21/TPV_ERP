package com.tpverp.saas.sync;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Opt-in maintenance operation; never accepts replacement event data. */
@RestController
@ConditionalOnProperty(name = "tpv.saas.sync-recovery.enabled", havingValue = "true")
public class AdminSyncRecoveryController {
    private final AdminSyncRecoveryService service;

    public AdminSyncRecoveryController(AdminSyncRecoveryService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/admin/sync/events/{eventId}/recover")
    public AdminSyncRecoveryService.Result recover(
            @PathVariable UUID eventId,
            @Valid @RequestBody AdminSyncRecoveryService.Request request) {
        return service.recover(eventId, request);
    }
}
