package com.tpverp.saas.loyalty;

import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/companies/{companyId}/loyalty-bootstrap-source")
public class MemberLoyaltyBootstrapAdminController {

    private final MemberLoyaltyBootstrapAdminService service;

    public MemberLoyaltyBootstrapAdminController(MemberLoyaltyBootstrapAdminService service) {
        this.service = service;
    }

    @PutMapping
    public LoyaltyApiModels.BootstrapSourceResponse designate(
            @PathVariable UUID companyId,
            @RequestBody LoyaltyApiModels.BootstrapSourceRequest request) {
        return service.designate(companyId, request);
    }
}

