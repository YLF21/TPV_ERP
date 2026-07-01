package com.tpverp.saas.sync;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sync")
public class SyncController {

    private final SyncEventService service;

    public SyncController(SyncEventService service) {
        this.service = service;
    }

    @PostMapping("/events")
    public SyncEventReceipt receive(
            @Valid @RequestBody SyncEventRequest request,
            @RequestHeader(name = "X-TPV-Installation-Token", required = false) String token) {
        return service.receive(request, token);
    }
}
