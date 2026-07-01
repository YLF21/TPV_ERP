package com.tpverp.saas.admin;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AdminService service;

    public AdminController(AdminService service) {
        this.service = service;
    }

    @PostMapping("/companies")
    public CreateCompanyResponse createCompany(@Valid @RequestBody CreateCompanyRequest request) {
        return service.createCompany(request);
    }

    @PostMapping("/licenses/{reference}/block")
    public AdminLicenseResponse block(@PathVariable String reference) {
        return service.block(reference);
    }

    @PostMapping("/licenses/{reference}/unblock")
    public AdminLicenseResponse unblock(@PathVariable String reference) {
        return service.unblock(reference);
    }
}
