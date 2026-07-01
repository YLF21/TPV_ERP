package com.tpverp.backend.licensing.api;

import com.tpverp.backend.licensing.application.LicensePreview;
import com.tpverp.backend.licensing.application.LicenseService;
import com.tpverp.backend.licensing.LicenseSaasAdminService;
import com.tpverp.backend.licensing.LicenseSaasLinkResponse;
import com.tpverp.backend.licensing.LicenseSaasLinkService;
import com.tpverp.backend.licensing.LicenseSaasValidationResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/v1/licenses")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('LICENSES_MANAGE')")
public class LicenseController {

    private final LicenseService licenseService;
    private final LicenseSaasAdminService saasAdmin;
    private final LicenseSaasLinkService saasLink;

    public LicenseController(
            LicenseService licenseService,
            LicenseSaasAdminService saasAdmin,
            LicenseSaasLinkService saasLink) {
        this.licenseService = licenseService;
        this.saasAdmin = saasAdmin;
        this.saasLink = saasLink;
    }

    @PostMapping("/preview")
    public LicensePreview preview(@Valid @RequestBody LicenseFileRequest request) {
        return licenseService.preview(request.license());
    }

    @PostMapping("/activate")
    public LicensePreview activate(@Valid @RequestBody ActivateLicenseRequest request) {
        return licenseService.activate(request.license(), request.confirmationHash());
    }

    @PostMapping("/link-saas")
    public LinkSaasResponse linkSaas(@Valid @RequestBody LinkSaasRequest request) {
        return LinkSaasResponse.from(saasLink.link(request.pairingCode()));
    }

    @GetMapping
    public List<LicenseService.LicenseHistoryItem> history() {
        return licenseService.history();
    }

    @PostMapping("/{reference}/block")
    public LicenseSaasValidationResponse block(@PathVariable String reference) {
        return saasAdmin.block(reference);
    }

    @PostMapping("/{reference}/unblock")
    public LicenseSaasValidationResponse unblock(@PathVariable String reference) {
        return saasAdmin.unblock(reference);
    }

    public record LicenseFileRequest(@NotBlank String license) {
    }

    public record ActivateLicenseRequest(
            @NotBlank String license,
            @NotBlank String confirmationHash) {
    }

    public record LinkSaasRequest(@NotBlank String pairingCode) {
    }

    public record LinkSaasResponse(
            String licenseReference,
            UUID companyId,
            UUID storeId,
            Instant validUntil,
            String status,
            int maxWindows,
            int maxPda) {

        static LinkSaasResponse from(LicenseSaasLinkResponse response) {
            return new LinkSaasResponse(
                    response.licenseReference(),
                    response.companyId(),
                    response.storeId(),
                    response.validUntil(),
                    response.status().name(),
                    response.maxWindows(),
                    response.maxPda());
        }
    }
}
