package com.tpverp.backend.control;

import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/control/alerts")
@PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and (hasAuthority('CONTROL_ALERTS_READ') or hasAuthority('CONTROL_ALERTS_MANAGE')))")
public class ControlAlertController {

    private final ControlAlertService service;

    public ControlAlertController(ControlAlertService service) {
        this.service = service;
    }

    @GetMapping
    public Page<ControlAlertService.AlertSummaryView> list(
            @RequestParam(required = false) ControlAlertStatus status,
            @RequestParam(required = false) ControlAlertType type,
            @RequestParam(required = false) UUID ruleId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return service.list(status, type, ruleId, from, to, search, page, size);
    }

    @GetMapping("/groups")
    public java.util.List<ControlAlertService.RuleAlertCountView> groups(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return service.countsByRule(from, to);
    }

    @GetMapping("/summary")
    public ControlAlertService.AlertDashboardSummaryView summary() {
        return service.dashboardSummary();
    }

    @GetMapping("/{id}")
    public ControlAlertService.AlertDetailView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/{id}/review")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and hasAuthority('CONTROL_ALERTS_MANAGE'))")
    public ControlAlertService.AlertDetailView review(
            @PathVariable UUID id,
            @Valid @RequestBody ControlAlertService.TransitionRequest request,
            Authentication authentication) {
        return service.transition(id, ControlAlertStatus.REVIEWED, request, authentication);
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and hasAuthority('CONTROL_ALERTS_MANAGE'))")
    public ControlAlertService.AlertDetailView close(
            @PathVariable UUID id,
            @Valid @RequestBody ControlAlertService.TransitionRequest request,
            Authentication authentication) {
        return service.transition(id, ControlAlertStatus.CLOSED, request, authentication);
    }

    @PostMapping("/{id}/dismiss")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and hasAuthority('CONTROL_ALERTS_MANAGE'))")
    public ControlAlertService.AlertDetailView dismiss(
            @PathVariable UUID id,
            @Valid @RequestBody ControlAlertService.TransitionRequest request,
            Authentication authentication) {
        return service.transition(id, ControlAlertStatus.DISMISSED, request, authentication);
    }

    @GetMapping("/{id}/document")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and (hasAuthority('CONTROL_ALERTS_READ') or hasAuthority('CONTROL_ALERTS_MANAGE')) and hasAuthority('GESTION_VENTAS'))")
    public ControlAlertService.RelatedDocumentView relatedDocument(@PathVariable UUID id) {
        return service.relatedDocument(id);
    }
}
