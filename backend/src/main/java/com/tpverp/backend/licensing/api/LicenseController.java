package com.tpverp.backend.licensing.api;

import com.tpverp.backend.licensing.application.LicensePreview;
import com.tpverp.backend.licensing.application.LicenseService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
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

    public LicenseController(LicenseService licenseService) {
        this.licenseService = licenseService;
    }

    @PostMapping("/preview")
    public LicensePreview preview(@Valid @RequestBody LicenseFileRequest request) {
        return licenseService.preview(request.license());
    }

    @PostMapping("/activate")
    public LicensePreview activate(@Valid @RequestBody ActivateLicenseRequest request) {
        return licenseService.activate(request.license(), request.confirmationHash());
    }

    @GetMapping
    public List<LicenseService.LicenseHistoryItem> history() {
        return licenseService.history();
    }

    public record LicenseFileRequest(@NotBlank String license) {
    }

    public record ActivateLicenseRequest(
            @NotBlank String license,
            @NotBlank String confirmationHash) {
    }
}
