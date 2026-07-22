package com.tpverp.backend.verifactu;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/verifactu/admin")
public class VerifactuAdminActionController {

    private final VerifactuManualRetryService retries;

    public VerifactuAdminActionController(VerifactuManualRetryService retries) {
        this.retries = retries;
    }

    @PostMapping("/submissions/{recordId}/retry")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and hasAuthority('VERIFACTU_READ') and hasAuthority('VERIFACTU_MANAGE'))")
    public VerifactuManualRetryView retry(
            @PathVariable UUID recordId,
            @Valid @RequestBody VerifactuManualRetryRequest request) {
        return retries.retry(recordId, request);
    }
}
