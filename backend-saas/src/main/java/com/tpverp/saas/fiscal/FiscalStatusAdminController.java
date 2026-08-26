package com.tpverp.saas.fiscal;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/fiscal-status")
public class FiscalStatusAdminController {
    private final FiscalStatusAdminService service;

    public FiscalStatusAdminController(FiscalStatusAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<FiscalStatusAdminView> all() { return service.all(); }

    @GetMapping("/companies")
    public List<FiscalCompanyStatusAdminView> companies() { return service.companies(); }

    @GetMapping("/companies/{companyId}")
    public List<FiscalStatusAdminView> company(@PathVariable UUID companyId) {
        return service.company(companyId);
    }
}
