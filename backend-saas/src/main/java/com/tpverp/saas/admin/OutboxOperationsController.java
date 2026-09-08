package com.tpverp.saas.admin;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/outbox")
public class OutboxOperationsController {

    private final OutboxOperationsService operations;

    public OutboxOperationsController(OutboxOperationsService operations) {
        this.operations = operations;
    }

    @GetMapping("/failures")
    public OutboxFailurePageResponse failures(
            @RequestParam(required = false) String channel,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String cursor) {
        return operations.failures(channel, limit, cursor);
    }

    @PostMapping("/security/{id}/requeue")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void requeueSecurity(@PathVariable UUID id, @Valid @RequestBody OutboxResolutionRequest request) {
        operations.requeueSecurity(id, request.reason());
    }

    @PostMapping("/security/{id}/acknowledge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void acknowledgeSecurity(@PathVariable UUID id, @Valid @RequestBody OutboxResolutionRequest request) {
        operations.acknowledgeSecurity(id, request.reason());
    }

    @PostMapping("/integrations/{id}/requeue")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void requeueIntegration(@PathVariable UUID id, @Valid @RequestBody OutboxResolutionRequest request) {
        operations.requeueIntegration(id, request.reason());
    }

    @PostMapping("/integrations/{id}/acknowledge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void acknowledgeIntegration(@PathVariable UUID id, @Valid @RequestBody OutboxResolutionRequest request) {
        operations.acknowledgeIntegration(id, request.reason());
    }
}
