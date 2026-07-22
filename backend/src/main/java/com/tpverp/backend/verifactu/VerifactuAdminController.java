package com.tpverp.backend.verifactu;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/verifactu/admin")
public class VerifactuAdminController {

    private final VerifactuAdminService service;

    public VerifactuAdminController(VerifactuAdminService service) {
        this.service = service;
    }

    @GetMapping("/status")
    @PreAuthorize("hasRole('ADMIN')")
    public VerifactuAdminStatusView status() {
        return service.status();
    }

    @GetMapping("/queue")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and hasAuthority('VERIFACTU_READ'))")
    public List<FiscalSubmissionQueueItem> queue() {
        return service.queue();
    }

    @GetMapping("/clock")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and hasAuthority('VERIFACTU_READ'))")
    public VerifactuClockStatusView clock() {
        return service.clock();
    }

    @GetMapping("/records/{recordId}/attempts")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and hasAuthority('VERIFACTU_READ') and hasAuthority('VERIFACTU_MANAGE'))")
    public List<FiscalSubmissionAttemptView> attempts(@PathVariable UUID recordId) {
        return service.attempts(recordId);
    }

    @PostMapping("/activate-voluntary")
    @PreAuthorize("hasRole('ADMIN')")
    public VerifactuConfigurationView activateVoluntary() {
        return VerifactuConfigurationView.from(service.activateVoluntary());
    }

    @PostMapping("/deactivate-voluntary")
    @PreAuthorize("hasRole('ADMIN')")
    public VerifactuConfigurationView deactivateVoluntary() {
        return VerifactuConfigurationView.from(service.deactivateVoluntary());
    }

    @PostMapping("/retry-next")
    @PreAuthorize("hasRole('ADMIN')")
    public VerifactuWorkerResult retryNext() {
        return service.retryNext();
    }
}
