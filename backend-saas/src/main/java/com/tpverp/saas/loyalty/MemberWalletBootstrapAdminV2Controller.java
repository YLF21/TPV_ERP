package com.tpverp.saas.loyalty;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/admin/companies/{companyId}/member-wallet-bootstrap")
public class MemberWalletBootstrapAdminV2Controller {

    private final MemberWalletBootstrapAdminV2Service service;

    public MemberWalletBootstrapAdminV2Controller(MemberWalletBootstrapAdminV2Service service) {
        this.service = service;
    }

    @PostMapping
    public LoyaltyApiModels.WalletBootstrapStatus start(@PathVariable UUID companyId) {
        return service.start(companyId);
    }

    @GetMapping
    public LoyaltyApiModels.WalletBootstrapStatus status(@PathVariable UUID companyId) {
        return service.status(companyId);
    }

    @PostMapping("/{bootstrapId}/cancel")
    public LoyaltyApiModels.WalletBootstrapStatus cancel(
            @PathVariable UUID companyId,
            @PathVariable UUID bootstrapId) {
        return service.cancel(companyId, bootstrapId);
    }
}
