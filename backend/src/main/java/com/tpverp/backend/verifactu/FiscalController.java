package com.tpverp.backend.verifactu;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/fiscal")
public class FiscalController {
    private final FiscalModeTransitionService modes;
    private final FiscalEventService events;
    private final FiscalIntegrityService integrity;
    private final FiscalExportService exports;
    private final FiscalRequiredSubmissionService requiredSubmissions;

    public FiscalController(FiscalModeTransitionService modes,
            FiscalEventService events, FiscalIntegrityService integrity,
            FiscalExportService exports, FiscalRequiredSubmissionService requiredSubmissions) {
        this.modes = modes;
        this.events = events;
        this.integrity = integrity;
        this.exports = exports;
        this.requiredSubmissions = requiredSubmissions;
    }

    @GetMapping("/status")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('VERIFACTU_READ')")
    public FiscalStatusView status() { return modes.status(); }

    @PostMapping("/mode-transitions")
    @PreAuthorize("hasRole('ADMIN')")
    public FiscalStatusView transition(@Valid @RequestBody ModeTransitionRequest request) {
        return modes.transition(request.targetMode(), request.expectedVersion(),
                request.reason(), request.confirmation(), request.fechaFinVeriFactu(),
                request.aeatAckReference());
    }

    @GetMapping("/events")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('VERIFACTU_READ')")
    public List<FiscalEvent> events() {
        var company = modes.status().companyId();
        return events.findTop50(company);
    }

    @PostMapping("/integrity-checks")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('VERIFACTU_READ')")
    public FiscalIntegrityCheckView integrityCheck() {
        return integrity.check();
    }

    @PostMapping("/exports")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('VERIFACTU_READ')")
    public FiscalExportView export(@Valid @RequestBody FiscalExportRequest request) {
        return exports.export(request.kind(), request.periodStart(), request.periodEnd());
    }

    @PostMapping("/required-submissions")
    @PreAuthorize("hasRole('ADMIN')")
    public FiscalRequiredSubmissionView requiredSubmission(
            @Valid @RequestBody FiscalRequiredSubmissionRequest request) {
        return requiredSubmissions.register(request.reference());
    }

    @PostMapping("/required-submissions/{id}/exports")
    @PreAuthorize("hasRole('ADMIN')")
    public FiscalRequiredSubmissionExportView requiredSubmissionExport(
            @PathVariable UUID id,
            @Valid @RequestBody FiscalRequiredSubmissionExportRequest request) {
        return requiredSubmissions.export(id, request.kind(), request.periodStart(),
                request.periodEnd());
    }

    public record ModeTransitionRequest(
            @NotNull FiscalMode targetMode,
            long expectedVersion,
            @NotBlank String reason,
            boolean confirmation,
            LocalDate fechaFinVeriFactu,
            String aeatAckReference) {}
}
