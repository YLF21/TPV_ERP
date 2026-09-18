package com.tpverp.backend.control;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/control/alerts/view-preference")
@PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and (hasAuthority('CONTROL_ALERTS_READ') or hasAuthority('CONTROL_ALERTS_MANAGE')))")
public class ControlAlertViewPreferenceController {
    private final ControlAlertViewPreferenceService service;

    public ControlAlertViewPreferenceController(ControlAlertViewPreferenceService service) {
        this.service = service;
    }

    @GetMapping
    public ControlAlertViewPreferenceService.View get(Authentication authentication) {
        return service.get(authentication);
    }

    @PutMapping
    public ControlAlertViewPreferenceService.View save(
            @RequestBody ControlAlertViewPreferenceService.Settings settings,
            Authentication authentication) {
        return service.save(settings, authentication);
    }
}
