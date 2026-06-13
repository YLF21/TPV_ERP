package com.tpverp.backend.audit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
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

    @DeleteMapping("/{auditId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(
            @PathVariable UUID auditId,
            @Valid @RequestBody DeleteAuditRequest request) {
        auditService.delete(auditId, request.confirmation());
        return ResponseEntity.noContent().build();
    }

    public record DeleteAuditRequest(@NotBlank String confirmation) {
    }
}
