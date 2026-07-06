package com.tpverp.backend.document;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sales-reports/visualization-preferences")
public class ReportVisualizationPreferenceController {

    private final ReportVisualizationPreferenceService service;

    public ReportVisualizationPreferenceController(ReportVisualizationPreferenceService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','GESTION_CUENTAS','VENTA')")
    public ReportVisualizationPreferenceService.PreferenceListView list(
            @RequestParam @NotBlank String app,
            Authentication authentication) {
        return service.list(app, authentication);
    }

    @PutMapping("/{reportKey}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','GESTION_CUENTAS','VENTA')")
    public ReportVisualizationPreferenceService.PreferenceView save(
            @PathVariable String reportKey,
            @Valid @RequestBody ReportVisualizationPreferenceService.SavePreferenceRequest request,
            Authentication authentication) {
        return service.save(reportKey, request, authentication);
    }
}
