package com.tpverp.backend.party.loyalty.sync;

import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import com.tpverp.backend.shared.api.CorrelationIdFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sync/member-return-balance-recoveries")
@PreAuthorize("hasRole('ADMIN')")
public class MemberReturnBalanceRecoveryController {

    private final MemberReturnBalanceRecoveryRepairService service;

    public MemberReturnBalanceRecoveryController(MemberReturnBalanceRecoveryRepairService service) {
        this.service = service;
    }

    @GetMapping("/{returnRequestId}/preview")
    public MemberReturnBalanceRecoveryView preview(@PathVariable UUID returnRequestId) {
        return service.preview(returnRequestId);
    }

    @PostMapping("/{returnRequestId}/replay")
    public MemberReturnBalanceRecoveryView replay(
            @PathVariable UUID returnRequestId,
            @Valid @RequestBody MemberReturnBalanceRecoveryRequest request,
            HttpServletRequest httpRequest) {
        return service.replay(returnRequestId, request,
                CorrelationIdFilter.getOrCreate(httpRequest));
    }

}
