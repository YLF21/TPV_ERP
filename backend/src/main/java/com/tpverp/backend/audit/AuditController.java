package com.tpverp.backend.audit;

import java.time.Instant;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('AUDIT_READ')")
    public List<AuditService.AuditItem> query(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant until) {
        return auditService.query(from, until);
    }
}
