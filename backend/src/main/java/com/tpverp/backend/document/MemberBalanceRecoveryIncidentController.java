package com.tpverp.backend.document;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/member-balance-recovery")
@PreAuthorize("hasRole('ADMIN')")
public class MemberBalanceRecoveryIncidentController {

    private final MemberBalanceRecoveryIncidentService incidents;

    public MemberBalanceRecoveryIncidentController(
            MemberBalanceRecoveryIncidentService incidents) {
        this.incidents = incidents;
    }

    @GetMapping
    public List<MemberBalanceRecoveryIncidentView> list() {
        return incidents.list();
    }

    @PostMapping("/{sessionId}/retry")
    public MemberBalanceRecoveryIncidentView retry(
            @PathVariable UUID sessionId,
            @Valid @RequestBody ManualRetryRequest request) {
        return incidents.scheduleManualRetry(
                sessionId, request.expectedVersion(), request.reason());
    }

    public record ManualRetryRequest(
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotBlank @Size(max = 500) String reason) {
    }
}
