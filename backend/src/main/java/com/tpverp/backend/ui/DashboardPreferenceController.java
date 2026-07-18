package com.tpverp.backend.ui;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/gestion/dashboard/preference")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('APP_GESTION_ACCESS')")
public class DashboardPreferenceController {

    private final DashboardPreferenceService service;

    public DashboardPreferenceController(DashboardPreferenceService service) {
        this.service = service;
    }

    @GetMapping
    public DashboardPreferenceService.PreferenceView get(Authentication authentication) {
        return service.get(authentication);
    }

    @PutMapping
    public DashboardPreferenceService.PreferenceView save(
            @Valid @RequestBody DashboardPreferenceService.SavePreferenceRequest request,
            Authentication authentication) {
        return service.save(request, authentication);
    }
}
