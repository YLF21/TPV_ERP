package com.tpverp.backend.verifactu;

import com.tpverp.backend.security.gestion.GestionGroup;
import com.tpverp.backend.security.gestion.RequireGestionGroup;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import com.tpverp.backend.organization.CurrentOrganization;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.security.core.Authentication;

@RestController
@RequestMapping("/api/v1/fiscal")
@RequireGestionGroup(GestionGroup.FISCAL)
public class FiscalController {
    private final FiscalModeTransitionService modes;
    private final FiscalEventService events;
    private final FiscalIntegrityService integrity;
    private final FiscalExportService exports;
    private final FiscalRequiredSubmissionService requiredSubmissions;
    private final FiscalExportJobService fiscalJobs;
    private final FiscalExportJobLauncher fiscalLauncher;
    private final FiscalIntegrityJobService integrityJobs;
    private final FiscalIntegrityJobLauncher integrityLauncher;
    private final CurrentOrganization organization;

    public FiscalController(FiscalModeTransitionService modes,
            FiscalEventService events, FiscalIntegrityService integrity,
            FiscalExportService exports, FiscalRequiredSubmissionService requiredSubmissions,
            FiscalExportJobService fiscalJobs, FiscalExportJobLauncher fiscalLauncher,
            FiscalIntegrityJobService integrityJobs, FiscalIntegrityJobLauncher integrityLauncher,
            CurrentOrganization organization) {
        this.modes = modes;
        this.events = events;
        this.integrity = integrity;
        this.exports = exports;
        this.requiredSubmissions = requiredSubmissions;
        this.fiscalJobs = fiscalJobs;
        this.fiscalLauncher = fiscalLauncher;
        this.integrityJobs = integrityJobs;
        this.integrityLauncher = integrityLauncher;
        this.organization = organization;
    }

    @GetMapping("/status")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and "
            + "hasAuthority('VERIFACTU_READ'))")
    public FiscalStatusView status() { return modes.status(); }

    @PostMapping("/mode-transitions")
    @PreAuthorize("hasRole('ADMIN')")
    public FiscalStatusView transition(@Valid @RequestBody ModeTransitionRequest request) {
        return modes.transition(request.targetMode(), request.expectedVersion(),
                request.reason(), request.confirmation(), request.fechaFinVeriFactu(),
                request.aeatAckReference());
    }

    @GetMapping("/events")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and "
            + "hasAuthority('VERIFACTU_READ'))")
    public List<FiscalEventView> events() {
        return events.findTop50ViewsCurrent(organization);
    }

    @GetMapping("/events/cursor")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and "
            + "hasAuthority('VERIFACTU_READ'))")
    public FiscalEventReadCursorPage eventsCursor(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int size,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String cursor) {
        return events.findCursorViewsCurrent(organization, size, cursor);
    }

    @PostMapping("/integrity-checks")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and "
            + "hasAuthority('VERIFACTU_READ') and hasAuthority('VERIFACTU_MANAGE'))")
    public FiscalIntegrityCheckView integrityCheck() {
        integrity.rejectSynchronousIfTooLarge();
        return integrity.check();
    }

    @PostMapping("/integrity-jobs")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and "
            + "hasAuthority('VERIFACTU_READ') and hasAuthority('VERIFACTU_MANAGE'))")
    public FiscalIntegrityJobView createIntegrityJob(Authentication authentication) {
        var user = organization.currentUser(authentication);
        var view = integrityJobs.create(user.getId().toString());
        integrityLauncher.launch(view.id());
        return view;
    }

    @GetMapping("/integrity-jobs")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and "
            + "hasAuthority('VERIFACTU_READ'))")
    public org.springframework.data.domain.Page<FiscalIntegrityJobView> integrityJobs(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        var user = organization.currentUser(authentication);
        return integrityJobs.list(page, size, user.getId().toString(), isAdmin(authentication));
    }

    @GetMapping("/integrity-jobs/{id}")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and "
            + "hasAuthority('VERIFACTU_READ'))")
    public FiscalIntegrityJobView integrityJobStatus(@PathVariable UUID id,
            Authentication authentication) {
        var user = organization.currentUser(authentication);
        return integrityJobs.status(id, user.getId().toString(), isAdmin(authentication));
    }

    @PostMapping("/integrity-jobs/{id}/retry")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and "
            + "hasAuthority('VERIFACTU_READ') and hasAuthority('VERIFACTU_MANAGE'))")
    public FiscalIntegrityJobView retryIntegrityJob(@PathVariable UUID id,
            Authentication authentication) {
        var user = organization.currentUser(authentication);
        var view = integrityJobs.retry(id, user.getId().toString(), isAdmin(authentication));
        integrityLauncher.launch(view.id());
        return view;
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }

    @PostMapping("/exports")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and "
            + "hasAuthority('VERIFACTU_READ') and hasAuthority('VERIFACTU_MANAGE'))")
    public FiscalExportView export(@Valid @RequestBody FiscalExportRequest request) {
        var hasScopedSelection = request.recordIds() != null && !request.recordIds().isEmpty()
                || request.dateFrom() != null || request.dateTo() != null
                || request.documentNumber() != null || request.operation() != null
                || request.documentType() != null || request.fiscalMode() != null;
        return hasScopedSelection
                ? exports.export(request)
                : exports.export(request.kind(), request.periodStart(), request.periodEnd());
    }

    @PostMapping(value = "/exports/download", produces = "application/zip")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and "
            + "hasAuthority('VERIFACTU_READ') and hasAuthority('VERIFACTU_MANAGE'))")
    public ResponseEntity<StreamingResponseBody> exportDownload(
            @Valid @RequestBody FiscalExportRequest request) {
        // Resolve tenant, installation and all request validation before returning the async body.
        var plan = exports.prepareExportZip(request);
        StreamingResponseBody body = output -> exports.writeExportZip(plan, output);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header("Content-Disposition", "attachment; filename=exportacion-fiscal.zip")
                .body(body);
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

    @PostMapping("/required-submissions/{id}/export-jobs")
    @PreAuthorize("hasRole('ADMIN')")
    public FiscalExportJobView requiredSubmissionExportJob(
            @PathVariable UUID id,
            @Valid @RequestBody RequiredSubmissionJobRequest request,
            Authentication authentication) {
        var user = organization.currentUser(authentication);
        var view = fiscalJobs.createRequiredSubmissionJob(id, request.periodStart(), request.periodEnd(),
                user.getId().toString());
        fiscalLauncher.launch(view.id());
        return view;
    }

    public record RequiredSubmissionJobRequest(
            @NotNull OffsetDateTime periodStart,
            @NotNull OffsetDateTime periodEnd) {}

    public record ModeTransitionRequest(
            @NotNull FiscalMode targetMode,
            long expectedVersion,
            @NotBlank String reason,
            boolean confirmation,
            LocalDate fechaFinVeriFactu,
            String aeatAckReference) {}
}
