package com.tpverp.saas.admin;

import com.tpverp.saas.admin.OperationalIncidentModels.CancelMemberCategoryBootstrapRequest;
import com.tpverp.saas.admin.OperationalIncidentModels.CancellationResult;
import com.tpverp.saas.admin.OperationalIncidentModels.Incident;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/operational-incidents")
public class OperationalIncidentController {
    private final OperationalIncidentService service;

    public OperationalIncidentController(OperationalIncidentService service) {
        this.service = service;
    }

    @GetMapping
    public List<Incident> list(@RequestParam(required = false) UUID companyId) {
        return service.list(companyId);
    }

    @PostMapping("/companies/{companyId}/member-category-bootstraps/{bootstrapId}/cancel")
    public CancellationResult cancelMemberCategoryBootstrap(
            @PathVariable UUID companyId,
            @PathVariable UUID bootstrapId,
            @RequestBody CancelMemberCategoryBootstrapRequest request) {
        return service.cancelMemberCategoryBootstrap(companyId, bootstrapId, request);
    }
}
