package com.tpverp.backend.verifactu;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/verifactu/pos")
public class VerifactuPosController {

    private static final String POS_ACCESS =
            "hasRole('ADMIN') or hasAuthority('VENTA')";

    private final VerifactuPosService service;

    public VerifactuPosController(VerifactuPosService service) {
        this.service = service;
    }

    @GetMapping("/status")
    @PreAuthorize(POS_ACCESS)
    public VerifactuPosStatusView status(Authentication authentication) {
        return service.status(authentication);
    }

    @GetMapping("/queue")
    @PreAuthorize(POS_ACCESS)
    public List<VerifactuPosQueueItem> queue(
            @RequestParam(defaultValue = "50") int limit,
            Authentication authentication) {
        return service.queue(limit, authentication);
    }
}
