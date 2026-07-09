package com.tpverp.backend.promotion;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.terminal.CurrentTerminal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/promotional-coupons")
public class PromotionalCouponController {

    private static final String READ_PERMISSION =
            "hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','VENTA')";
    private static final String MANAGE_PERMISSION =
            "hasRole('ADMIN') or hasAuthority('GESTION_VENTAS')";

    private final PromotionalCouponService coupons;
    private final CurrentOrganization organization;
    private final CurrentTerminal terminal;

    public PromotionalCouponController(
            PromotionalCouponService coupons,
            CurrentOrganization organization,
            CurrentTerminal terminal) {
        this.coupons = coupons;
        this.organization = organization;
        this.terminal = terminal;
    }

    @GetMapping
    @PreAuthorize(READ_PERMISSION)
    public List<PromotionalCouponView> list(
            @RequestParam(required = false) PromotionalCouponStatus status,
            @RequestParam(required = false) String codeLast4) {
        return coupons.list(companyId(), status, codeLast4).stream()
                .map(PromotionalCouponView::from)
                .toList();
    }

    @PostMapping("/redeem")
    @PreAuthorize(READ_PERMISSION)
    public PromotionalCouponRedeemResponse redeem(
            @Valid @RequestBody PromotionalCouponRedeemRequest request,
            Authentication authentication) {
        var store = organization.currentStore();
        var user = organization.currentUser(authentication);
        var result = coupons.redeem(new PromotionalCouponService.RedemptionCommand(
                store.getEmpresa().getId(),
                store.getId(),
                request.documentId(),
                user.getId(),
                terminal.terminalId(authentication),
                request.customerId(),
                request.memberId(),
                request.memberCategoryId(),
                request.code(),
                request.pendingDocumentAmount()));
        return PromotionalCouponRedeemResponse.from(result);
    }

    @PatchMapping("/{id}/cancel")
    @PreAuthorize(MANAGE_PERMISSION)
    public PromotionalCouponView cancel(
            @PathVariable UUID id,
            @Valid @RequestBody PromotionalCouponAdminActionRequest request,
            Authentication authentication) {
        return PromotionalCouponView.from(coupons.cancel(adminCommand(id, request, authentication)));
    }

    @PatchMapping("/{id}/reactivate")
    @PreAuthorize(MANAGE_PERMISSION)
    public PromotionalCouponView reactivate(
            @PathVariable UUID id,
            @Valid @RequestBody PromotionalCouponAdminActionRequest request,
            Authentication authentication) {
        return PromotionalCouponView.from(coupons.reactivate(adminCommand(id, request, authentication)));
    }

    private PromotionalCouponService.AdminActionCommand adminCommand(
            UUID id,
            PromotionalCouponAdminActionRequest request,
            Authentication authentication) {
        return new PromotionalCouponService.AdminActionCommand(
                companyId(),
                id,
                organization.currentUser(authentication).getId(),
                request.reason());
    }

    private UUID companyId() {
        return organization.currentCompany().getId();
    }
}
