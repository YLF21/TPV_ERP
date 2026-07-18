package com.tpverp.backend.licensing.api;

import com.tpverp.backend.licensing.application.LicensePreview;
import com.tpverp.backend.licensing.application.LicenseService;
import com.tpverp.backend.licensing.LicenseSaasAdminService;
import com.tpverp.backend.licensing.LicenseSaasLinkResult;
import com.tpverp.backend.licensing.LicenseSaasLinkService;
import com.tpverp.backend.licensing.LicenseSaasValidationResponse;
import com.tpverp.backend.licensing.LicenseSaasValidationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/licenses")
public class LicenseController {

    private final LicenseService licenseService;
    private final LicenseSaasAdminService saasAdmin;
    private final LicenseSaasLinkService saasLink;
    private final LicenseSaasValidationService saasValidation;
    private final boolean localFileActivationEnabled;

    public LicenseController(
            LicenseService licenseService,
            LicenseSaasAdminService saasAdmin,
            LicenseSaasLinkService saasLink,
            LicenseSaasValidationService saasValidation,
            @Value("${tpv.license.local-file-activation-enabled:false}") boolean localFileActivationEnabled) {
        this.licenseService = licenseService;
        this.saasAdmin = saasAdmin;
        this.saasLink = saasLink;
        this.saasValidation = saasValidation;
        this.localFileActivationEnabled = localFileActivationEnabled;
    }

    @PostMapping("/preview")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('LICENSES_MANAGE')")
    public LicensePreview preview(@Valid @RequestBody LicenseFileRequest request) {
        requireLocalFileActivationEnabled();
        return licenseService.preview(request.license());
    }

    @PostMapping("/activate")
    @PreAuthorize("hasRole('ADMIN')")
    public LicensePreview activate(@Valid @RequestBody ActivateLicenseRequest request) {
        requireLocalFileActivationEnabled();
        return licenseService.activate(request.license(), request.confirmationHash());
    }

    @PostMapping("/link-saas")
    @PreAuthorize("hasRole('ADMIN')")
    public LinkSaasResponse linkSaas(@Valid @RequestBody LinkSaasRequest request) {
        return LinkSaasResponse.from(saasLink.link(request.pairingCode()));
    }

    @PostMapping("/validate-saas")
    @PreAuthorize("hasRole('ADMIN')")
    public LicenseSaasValidationResponse validateSaas() {
        return saasValidation.validateActiveLicense();
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('LICENSES_MANAGE')")
    public List<LicenseService.LicenseHistoryItem> history() {
        return licenseService.history();
    }

    @PostMapping("/{reference}/block")
    @PreAuthorize("hasRole('ADMIN')")
    public LicenseSaasValidationResponse block(@PathVariable String reference) {
        return saasAdmin.block(reference);
    }

    @PostMapping("/{reference}/unblock")
    @PreAuthorize("hasRole('ADMIN')")
    public LicenseSaasValidationResponse unblock(@PathVariable String reference) {
        return saasAdmin.unblock(reference);
    }

    private void requireLocalFileActivationEnabled() {
        if (!localFileActivationEnabled) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "message.license.local_file_activation_disabled");
        }
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
            UUID serverTerminalId,
            Instant validUntil,
            String status,
            int maxWindows,
            int maxPda) {

        static LinkSaasResponse from(LicenseSaasLinkResult result) {
            var response = result.license();
            return new LinkSaasResponse(
                    response.licenseReference(),
                    result.localCompanyId(),
                    result.localStoreId(),
                    result.serverTerminalId(),
                    response.validUntil(),
                    response.status().name(),
                    response.maxWindows(),
                    response.maxPda());
        }
    }
}
