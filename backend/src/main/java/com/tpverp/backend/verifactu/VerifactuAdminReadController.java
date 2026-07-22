package com.tpverp.backend.verifactu;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/verifactu/admin")
@PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and hasAuthority('VERIFACTU_READ'))")
public class VerifactuAdminReadController {

    private final VerifactuAdminReadService service;
    private final VerifactuAdminReviewReadService reviewService;
    private final VerifactuResolutionPolicyService resolutions;

    public VerifactuAdminReadController(
            VerifactuAdminReadService service,
            VerifactuAdminReviewReadService reviewService,
            VerifactuResolutionPolicyService resolutions) {
        this.service = service;
        this.reviewService = reviewService;
        this.resolutions = resolutions;
    }

    @GetMapping("/summary")
    public VerifactuAdminSummaryView summary() {
        return service.summary();
    }

    @GetMapping("/submissions")
    public VerifactuAdminSubmissionPage submissions(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) FiscalSubmissionStatus status,
            @RequestParam(required = false) FiscalDocumentType documentType,
            @RequestParam(required = false) FiscalRecordOperation operation,
            @RequestParam(required = false) String documentNumber,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return service.submissions(
                dateFrom, dateTo, status, documentType, operation,
                documentNumber, page, size);
    }

    @GetMapping("/defective-records")
    public VerifactuAdminDefectiveRecordPage defectiveRecords(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) FiscalSubmissionStatus status,
            @RequestParam(required = false) FiscalDocumentType documentType,
            @RequestParam(required = false) FiscalRecordOperation operation,
            @RequestParam(required = false) String documentNumber,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return reviewService.defectiveRecords(
                dateFrom, dateTo, status, documentType, operation,
                documentNumber, page, size);
    }

    @GetMapping("/submissions/{recordId}/attempts")
    public VerifactuAdminAttemptPage attempts(
            @PathVariable UUID recordId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return reviewService.attempts(recordId, page, size);
    }

    @GetMapping("/diagnostics")
    public VerifactuAdminDiagnosticView diagnostics() {
        return reviewService.diagnostics();
    }

    @GetMapping("/submissions/{recordId}/resolution")
    public VerifactuResolutionView resolution(
            @PathVariable UUID recordId,
            Authentication authentication) {
        return resolutions.resolution(recordId, authentication);
    }
}
