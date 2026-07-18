package com.tpverp.backend.control;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/control/rules")
@PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and hasAuthority('CONTROL_RULES_MANAGE'))")
public class ControlRuleController {

    private final ControlRuleService service;

    public ControlRuleController(ControlRuleService service) {
        this.service = service;
    }

    @GetMapping
    public List<ControlRuleService.RuleView> list() {
        return service.list();
    }

    @GetMapping("/catalog")
    public List<ControlRuleService.RuleCatalogView> catalog() {
        return service.catalog();
    }

    @GetMapping("/{id}")
    public ControlRuleService.RuleView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping("/{id}/versions")
    public List<ControlRuleService.RuleVersionView> versions(@PathVariable UUID id) {
        return service.versions(id);
    }

    @PostMapping
    public ControlRuleService.RuleView create(
            @Valid @RequestBody ControlRuleService.CreateRuleRequest request,
            Authentication authentication) {
        return service.create(request, authentication);
    }

    @PutMapping("/{id}")
    public ControlRuleService.RuleView update(
            @PathVariable UUID id,
            @Valid @RequestBody ControlRuleService.UpdateRuleRequest request,
            Authentication authentication) {
        return service.update(id, request, authentication);
    }
}
