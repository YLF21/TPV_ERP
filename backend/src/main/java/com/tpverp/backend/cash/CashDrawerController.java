package com.tpverp.backend.cash;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pos/cash-drawer")
public class CashDrawerController {

    private final CashDrawerService service;

    public CashDrawerController(CashDrawerService service) {
        this.service = service;
    }

    @PostMapping("/open-authorizations")
    @PreAuthorize("isAuthenticated()")
    public CashDrawerService.AuthorizationView authorize(
            @Valid @RequestBody AuthorizationRequest request,
            Authentication authentication) {
        return service.authorize(
                request.terminalId(),
                request.authorizerUsername(),
                request.authorizerPassword(),
                authentication);
    }

    @PostMapping("/open-authorizations/{operationId}/result")
    @PreAuthorize("isAuthenticated()")
    public CashDrawerService.CompletionView complete(
            @PathVariable UUID operationId,
            @Valid @RequestBody CompletionRequest request,
            Authentication authentication) {
        return service.complete(
                operationId,
                request.opened(),
                request.errorCode(),
                request.errorMessage(),
                authentication);
    }

    public record AuthorizationRequest(
            @NotNull UUID terminalId,
            @Size(max = 128) String authorizerUsername,
            @Size(max = 128) String authorizerPassword) {
    }

    public record CompletionRequest(
            boolean opened,
            @Size(max = 64) String errorCode,
            @Size(max = 300) String errorMessage) {
    }
}
