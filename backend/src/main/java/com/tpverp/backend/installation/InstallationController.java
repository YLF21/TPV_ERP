package com.tpverp.backend.installation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/installation")
public class InstallationController {

    private final InstallationStatusService statusService;

    public InstallationController(InstallationStatusService statusService) {
        this.statusService = statusService;
    }

    @GetMapping("/status")
    public InstallationStatusService.InstallationStatus status() {
        return statusService.status();
    }

    @GetMapping("/license-request")
    public InstallationStatusService.LicenseRequest licenseRequest() {
        return statusService.licenseRequest();
    }
}
