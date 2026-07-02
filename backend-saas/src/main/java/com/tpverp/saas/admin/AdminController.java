package com.tpverp.saas.admin;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
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

    @GetMapping("/licenses")
    public List<LicenseSummaryResponse> licenses() {
        return service.licenses();
    }

    @GetMapping("/installations")
    public List<InstallationSummaryResponse> installations() {
        return service.installations();
    }

    @PostMapping("/licenses/{reference}/renew")
    public AdminLicenseResponse renew(
            @PathVariable String reference,
            @Valid @RequestBody RenewLicenseRequest request) {
        return service.renew(reference, request);
    }

    @PostMapping("/licenses/{reference}/pairing-codes")
    public PairingCodeResponse regeneratePairingCode(@PathVariable String reference) {
        return service.regeneratePairingCode(reference);
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
