package com.tpverp.saas.tenant;

import com.tpverp.saas.access.TenantAccessResponse;
import com.tpverp.saas.access.TenantAccessService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TenantAccessController {
    private final TenantAccessService access;
    public TenantAccessController(TenantAccessService access) { this.access = access; }

    @GetMapping("/api/v1/tenant/access")
    public TenantAccessResponse access() {
        return access.access(TenantContextHolder.current().username());
    }
}
