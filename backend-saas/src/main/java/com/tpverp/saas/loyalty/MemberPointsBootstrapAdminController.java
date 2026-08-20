package com.tpverp.saas.loyalty;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/admin/companies/{companyId}/member-points-bootstrap")
public class MemberPointsBootstrapAdminController {
    private final MemberPointsBootstrapAdminService service;
    public MemberPointsBootstrapAdminController(MemberPointsBootstrapAdminService service){this.service=service;}
    @PostMapping public LoyaltyApiModels.PointsBootstrapStatus start(@PathVariable UUID companyId){return service.start(companyId);}
    @GetMapping public LoyaltyApiModels.PointsBootstrapStatus status(@PathVariable UUID companyId){return service.status(companyId);}
    @PostMapping("/{bootstrapId}/cancel")
    public LoyaltyApiModels.PointsBootstrapStatus cancel(@PathVariable UUID companyId,@PathVariable UUID bootstrapId){
        return service.cancel(companyId,bootstrapId);
    }
}
