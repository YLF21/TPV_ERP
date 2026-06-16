package com.tpverp.backend.verifactu;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
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
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS')")
    public VerifactuAdminStatusView status() {
        return service.status();
    }

    @GetMapping("/queue")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS')")
    public List<FiscalSubmissionQueueItem> queue() {
        return service.queue();
    }

    @PostMapping("/retry-next")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS')")
    public VerifactuWorkerResult retryNext() {
        return service.retryNext();
    }
}
