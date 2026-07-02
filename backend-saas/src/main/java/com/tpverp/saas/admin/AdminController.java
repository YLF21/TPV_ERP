package com.tpverp.saas.admin;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AdminService service;
    private final AdminAuditService audit;

    public AdminController(AdminService service, AdminAuditService audit) {
        this.service = service;
        this.audit = audit;
    }

    @PostMapping("/companies")
    public CreateCompanyResponse createCompany(@Valid @RequestBody CreateCompanyRequest request) {
        return service.createCompany(request);
    }

    @PutMapping("/companies/{companyId}")
    public LicenseSummaryResponse editCompany(
            @PathVariable UUID companyId,
            @Valid @RequestBody EditCompanyDataRequest request) {
        return service.editCompany(companyId, request);
    }

    @PutMapping("/users/{username}/password")
    public void changePassword(
            @PathVariable String username,
            @Valid @RequestBody ChangeAdminPasswordRequest request) {
        service.changePassword(username, request);
    }

    @GetMapping("/licenses")
    public List<LicenseSummaryResponse> licenses() {
        return service.licenses();
    }

    @GetMapping("/installations")
    public List<InstallationSummaryResponse> installations() {
        return service.installations();
    }

    @GetMapping("/audit")
    public List<AdminAuditLogResponse> audit() {
        return audit.recent();
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
