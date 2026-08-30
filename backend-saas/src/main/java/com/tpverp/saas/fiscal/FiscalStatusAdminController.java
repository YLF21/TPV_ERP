package com.tpverp.saas.fiscal;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    @GetMapping("/page")
    public FiscalStatusAdminPage<FiscalStatusAdminView> page(
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) UUID storeId,
            @RequestParam(required = false) UUID installationId,
            @RequestParam(required = false) String effectiveMode,
            @RequestParam(required = false) String activationState,
            @RequestParam(required = false) Boolean stale,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int size) {
        return service.page(companyId, storeId, installationId, effectiveMode,
                activationState, stale, cursor, size);
    }

    @GetMapping("/companies")
    public List<FiscalCompanyStatusAdminView> companies() { return service.companies(); }

    @GetMapping("/companies/page")
    public FiscalStatusAdminPage<FiscalCompanyStatusAdminView> companyPage(
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) String companyName,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int size) {
        return service.companyPage(companyId, companyName, cursor, size);
    }

    @GetMapping("/companies/{companyId}")
    public List<FiscalStatusAdminView> company(@PathVariable UUID companyId) {
        return service.company(companyId);
    }
}
