package com.tpverp.saas.access;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/tenant-users/{username}/access")
public class TenantAccessAdminController {
    private final TenantAccessService access;
    public TenantAccessAdminController(TenantAccessService access) { this.access = access; }

    @GetMapping
    public TenantAccessResponse get(@PathVariable String username) { return access.access(username); }

    @PutMapping("/companies/{companyId}")
    public TenantAccessResponse replace(@PathVariable String username, @PathVariable UUID companyId,
            @Valid @RequestBody UpdateTenantAccessRequest request) {
        return access.replace(username, companyId, request);
    }

    @DeleteMapping("/companies/{companyId}")
    public TenantAccessResponse revoke(@PathVariable String username, @PathVariable UUID companyId) {
        return access.revoke(username, companyId);
    }
}
