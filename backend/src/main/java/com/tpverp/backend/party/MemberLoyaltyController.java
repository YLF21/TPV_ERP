package com.tpverp.backend.party;

import com.tpverp.backend.security.gestion.GestionGroup;
import com.tpverp.backend.security.gestion.RequireGestionGroup;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MemberLoyaltyController {

    private final MemberLoyaltyService service;
    private final MemberCategoryAuthorityRoutingService categoryAuthority;

    public MemberLoyaltyController(
            MemberLoyaltyService service,
            MemberCategoryAuthorityRoutingService categoryAuthority) {
        this.service = service;
        this.categoryAuthority = categoryAuthority;
    }

    @GetMapping("/api/v1/members")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('CUSTOMERS_READ','GESTION_CLIENTE_PROVEEDOR')")
    public List<MemberLoyaltyService.MemberDirectoryView> list() {
        return service.list();
    }

    @GetMapping("/api/v1/members/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('CUSTOMERS_READ','GESTION_CLIENTE_PROVEEDOR')")
    public MemberLoyaltyService.MemberView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping("/api/v1/members/{id}/movements")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('CUSTOMERS_READ','GESTION_CLIENTE_PROVEEDOR')")
    public List<MemberLoyaltyService.MemberMovementView> movements(@PathVariable UUID id) {
        return service.movements(id);
    }

    @GetMapping("/api/v1/customers/{customerId}/member-wallet")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('CUSTOMERS_READ','GESTION_CLIENTE_PROVEEDOR','VENTA')")
    public MemberLoyaltyService.MemberWalletView wallet(@PathVariable UUID customerId) {
        return service.wallet(customerId);
    }

    @PostMapping("/api/v1/members/{id}/balance-adjustments")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('CUSTOMERS_WRITE','GESTION_CLIENTE_PROVEEDOR')")
    public MemberLoyaltyService.MemberMovementView adjustBalance(
            @PathVariable UUID id,
            @Valid @RequestBody BalanceAdjustmentRequest request) {
        return service.adjustBalance(id, request.amount(), request.reason());
    }

    @PostMapping("/api/v1/members/{id}/points-adjustments")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('CUSTOMERS_WRITE','GESTION_CLIENTE_PROVEEDOR')")
    public MemberLoyaltyService.MemberMovementView adjustPoints(
            @PathVariable UUID id,
            @Valid @RequestBody PointsAdjustmentRequest request) {
        return service.adjustPoints(id, request.points(), request.reason());
    }

    @PutMapping("/api/v1/members/{id}/category")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('CUSTOMERS_WRITE','GESTION_CLIENTE_PROVEEDOR')")
    public MemberLoyaltyService.MemberView setCategory(
            @PathVariable UUID id,
            @Valid @RequestBody SetCategoryRequest request) {
        return categoryAuthority.centralizedOrThrow()
                ? categoryAuthority.setCategory(
                        id, request.categoryId(), request.lockAutomatic(), request.reason())
                : service.setCategory(
                        id, request.categoryId(), request.lockAutomatic(), request.reason());
    }

    @GetMapping("/api/v1/member-categories")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('CUSTOMERS_READ','GESTION_CLIENTE_PROVEEDOR')")
    public List<MemberLoyaltyService.MemberCategoryView> categories() {
        return service.categories();
    }

    @PostMapping("/api/v1/member-categories")
    @PreAuthorize("hasRole('ADMIN')")
    public MemberLoyaltyService.MemberCategoryView createCategory(
            @Valid @RequestBody CategoryRequest request) {
        var command = request.command();
        return categoryAuthority.centralizedOrThrow()
                ? categoryAuthority.createCategory(command)
                : service.createCategory(command);
    }

    @PutMapping("/api/v1/member-categories/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public MemberLoyaltyService.MemberCategoryView updateCategory(
            @PathVariable UUID id,
            @Valid @RequestBody CategoryRequest request) {
        var command = request.command();
        return categoryAuthority.centralizedOrThrow()
                ? categoryAuthority.updateCategory(id, command)
                : service.updateCategory(id, command);
    }

    @PatchMapping("/api/v1/member-categories/{id}/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deactivateCategory(@PathVariable UUID id) {
        if (categoryAuthority.centralizedOrThrow()) {
            categoryAuthority.deactivateCategory(id);
        } else {
            service.deactivateCategory(id);
        }
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/api/v1/member-categories/{id}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    public MemberLoyaltyService.MemberCategoryView activateCategory(@PathVariable UUID id) {
        return categoryAuthority.centralizedOrThrow()
                ? categoryAuthority.activateCategory(id)
                : service.activateCategory(id);
    }

    @GetMapping("/api/v1/member-settings")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('CUSTOMERS_READ','GESTION_CLIENTE_PROVEEDOR')")
    @RequireGestionGroup(GestionGroup.CONFIGURACION)
    public MemberLoyaltyService.MemberSettingsView settings() {
        return service.settings();
    }

    @PutMapping("/api/v1/member-settings")
    @PreAuthorize("hasRole('ADMIN')")
    @RequireGestionGroup(GestionGroup.CONFIGURACION)
    public MemberLoyaltyService.MemberSettingsView updateSettings(
            @Valid @RequestBody SettingsRequest request) {
        return service.updateSettings(request.command());
    }

    @GetMapping("/api/v1/commercial-contact-channels")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('CUSTOMERS_READ','GESTION_CLIENTE_PROVEEDOR','VENTA')")
    public List<MemberLoyaltyService.CommercialChannelView> channels() {
        return service.channels();
    }

    @PostMapping("/api/v1/commercial-contact-channels")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('CUSTOMERS_WRITE','GESTION_CLIENTE_PROVEEDOR')")
    public MemberLoyaltyService.CommercialChannelView createChannel(
            @Valid @RequestBody ChannelRequest request) {
        return service.createChannel(request.command());
    }

    @PutMapping("/api/v1/commercial-contact-channels/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('CUSTOMERS_WRITE','GESTION_CLIENTE_PROVEEDOR')")
    public MemberLoyaltyService.CommercialChannelView updateChannel(
            @PathVariable UUID id,
            @Valid @RequestBody ChannelRequest request) {
        return service.updateChannel(id, request.command());
    }

    @GetMapping("/api/v1/member-card-deliveries")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('CUSTOMERS_READ','GESTION_CLIENTE_PROVEEDOR')")
    public List<MemberLoyaltyService.MemberCardDeliveryView> cardDeliveries(
            @RequestParam(required = false) MemberCardDeliveryStatus status,
            @RequestParam(required = false) UUID memberId) {
        return service.cardDeliveries(status, memberId);
    }

    @PatchMapping("/api/v1/member-card-deliveries/{id}/retry")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('CUSTOMERS_WRITE','GESTION_CLIENTE_PROVEEDOR')")
    public MemberLoyaltyService.MemberCardDeliveryView retryCardDelivery(@PathVariable UUID id) {
        return service.retryCardDelivery(id);
    }

    public record BalanceAdjustmentRequest(@NotNull BigDecimal amount, @NotBlank String reason) {
    }

    public record PointsAdjustmentRequest(long points, @NotBlank String reason) {
    }

    public record SetCategoryRequest(UUID categoryId, boolean lockAutomatic, String reason) {
    }

    public record CategoryRequest(
            @NotBlank String name,
            @jakarta.validation.constraints.Min(0) long minPoints,
            @NotNull BigDecimal discountPercent,
            boolean discountEnabled,
            boolean manualOnly,
            int sortOrder) {

        public CategoryRequest(String name, long minPoints, BigDecimal discountPercent,
                boolean discountEnabled, int sortOrder) {
            this(name, minPoints, discountPercent, discountEnabled, false, sortOrder);
        }

        MemberLoyaltyService.MemberCategoryCommand command() {
            return new MemberLoyaltyService.MemberCategoryCommand(
                    name, minPoints, discountPercent, discountEnabled, manualOnly, sortOrder);
        }
    }

    public record SettingsRequest(
            boolean balanceAccrualEnabled,
            @NotNull @jakarta.validation.constraints.DecimalMin("0.01")
            BigDecimal balanceAccrualBaseAmount,
            @NotNull @jakarta.validation.constraints.DecimalMin("0.00")
            @jakarta.validation.constraints.DecimalMax("100.00") BigDecimal balanceAccrualPercent,
            @NotNull BalanceExpirationPolicy balanceExpirationPolicy,
            boolean pointsAccrualEnabled,
            @NotNull @jakarta.validation.constraints.DecimalMin("0.01")
            BigDecimal pointsAccrualBaseAmount,
            @NotNull @jakarta.validation.constraints.DecimalMin("0.00")
            @jakarta.validation.constraints.DecimalMax("1000.00") BigDecimal pointsPerEuro,
            boolean categoryAutoEnabled,
            boolean memberWelcomeEnabled,
            @NotNull MemberCardCodeFormat memberCardCodeFormat,
            String welcomeSubjectTemplate,
            String welcomeBodyTemplate) {

        MemberLoyaltyService.MemberSettingsCommand command() {
            return new MemberLoyaltyService.MemberSettingsCommand(
                    balanceAccrualEnabled, balanceAccrualBaseAmount,
                    balanceAccrualPercent, balanceExpirationPolicy,
                    pointsAccrualEnabled, pointsAccrualBaseAmount, pointsPerEuro,
                    categoryAutoEnabled, memberWelcomeEnabled, memberCardCodeFormat,
                    welcomeSubjectTemplate, welcomeBodyTemplate);
        }
    }

    public record ChannelRequest(@NotBlank String code, @NotBlank String name, boolean active) {

        MemberLoyaltyService.CommercialChannelCommand command() {
            return new MemberLoyaltyService.CommercialChannelCommand(code, name, active);
        }
    }
}
