package com.tpverp.backend.control;

import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/control/alerts")
@PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and (hasAuthority('CONTROL_ALERTS_READ') or hasAuthority('CONTROL_ALERTS_MANAGE')))")
public class ControlAlertAnalyticsController {

    private final ControlAlertAnalyticsService service;

    public ControlAlertAnalyticsController(ControlAlertAnalyticsService service) {
        this.service = service;
    }

    @GetMapping("/analytics")
    public ControlAlertAnalyticsService.AnalyticsView analytics(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "24") int overdueHours) {
        return service.analytics(from, to, overdueHours);
    }

    @GetMapping("/escalation-candidates")
    public ControlAlertAnalyticsService.EscalationCandidatesView escalationCandidates(
            @RequestParam int hours,
            @RequestParam(defaultValue = "25") int limit) {
        return service.escalationCandidates(hours, limit);
    }
}
