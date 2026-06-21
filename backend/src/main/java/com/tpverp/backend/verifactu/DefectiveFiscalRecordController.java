package com.tpverp.backend.verifactu;

import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/verifactu/defective-records")
public class DefectiveFiscalRecordController {

    private final DefectiveFiscalRecordService service;
    private final FiscalSubmissionAttemptService attempts;
    private final FiscalCorrectionService corrections;

    public DefectiveFiscalRecordController(
            DefectiveFiscalRecordService service,
            FiscalSubmissionAttemptService attempts,
            FiscalCorrectionService corrections) {
        this.service = service;
        this.attempts = attempts;
        this.corrections = corrections;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS')")
    public List<DefectiveFiscalRecordView> list() {
        return service.list();
    }

    @GetMapping("/{recordId}/attempts")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS')")
    public List<FiscalSubmissionAttemptView> attempts(@PathVariable UUID recordId) {
        return attempts.history(recordId).stream()
                .map(FiscalSubmissionAttemptView::from)
                .toList();
    }

    @PostMapping("/{recordId}/corrections")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS')")
    public FiscalCorrectionView correct(
            @PathVariable UUID recordId,
            @Valid @RequestBody FiscalCorrectionRequest request,
            Authentication authentication) {
        return corrections.correct(recordId, request, authentication);
    }
}
