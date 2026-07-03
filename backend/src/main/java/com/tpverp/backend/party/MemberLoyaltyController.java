package com.tpverp.backend.party;

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

    public MemberLoyaltyController(MemberLoyaltyService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/members/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('CUSTOMERS_READ')")
    public MemberLoyaltyService.MemberView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping("/api/v1/members/{id}/movements")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('CUSTOMERS_READ')")
    public List<MemberLoyaltyService.MemberMovementView> movements(@PathVariable UUID id) {
        return service.movements(id);
    }

    @PostMapping("/api/v1/members/{id}/balance-adjustments")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('CUSTOMERS_WRITE')")
    public MemberLoyaltyService.MemberMovementView adjustBalance(
            @PathVariable UUID id,
            @Valid @RequestBody BalanceAdjustmentRequest request) {
        return service.adjustBalance(id, request.amount(), request.reason());
    }

    @PostMapping("/api/v1/members/{id}/points-adjustments")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('CUSTOMERS_WRITE')")
    public MemberLoyaltyService.MemberMovementView adjustPoints(
            @PathVariable UUID id,
            @Valid @RequestBody PointsAdjustmentRequest request) {
        return service.adjustPoints(id, request.points(), request.reason());
    }

    @PutMapping("/api/v1/members/{id}/category")
    @PreAuthorize("hasRole('ADMIN')")
    public MemberLoyaltyService.MemberView setCategory(
            @PathVariable UUID id,
            @Valid @RequestBody SetCategoryRequest request) {
        return service.setCategory(id, request.categoryId(), request.lockAutomatic(), request.reason());
    }

    @GetMapping("/api/v1/member-categories")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('CUSTOMERS_READ')")
    public List<MemberLoyaltyService.MemberCategoryView> categories() {
        return service.categories();
    }

    @PostMapping("/api/v1/member-categories")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('CUSTOMERS_WRITE')")
    public MemberLoyaltyService.MemberCategoryView createCategory(
            @Valid @RequestBody CategoryRequest request) {
        return service.createCategory(request.command());
    }

    @PutMapping("/api/v1/member-categories/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('CUSTOMERS_WRITE')")
    public MemberLoyaltyService.MemberCategoryView updateCategory(
            @PathVariable UUID id,
            @Valid @RequestBody CategoryRequest request) {
        return service.updateCategory(id, request.command());
    }

    @PatchMapping("/api/v1/member-categories/{id}/deactivate")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('CUSTOMERS_WRITE')")
    public ResponseEntity<Void> deactivateCategory(@PathVariable UUID id) {
        service.deactivateCategory(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/member-settings")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('CUSTOMERS_READ')")
    public MemberLoyaltyService.MemberSettingsView settings() {
        return service.settings();
    }

    @PutMapping("/api/v1/member-settings")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('CUSTOMERS_WRITE')")
    public MemberLoyaltyService.MemberSettingsView updateSettings(
            @Valid @RequestBody SettingsRequest request) {
        return service.updateSettings(request.command());
    }

    @GetMapping("/api/v1/commercial-contact-channels")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('CUSTOMERS_READ')")
    public List<MemberLoyaltyService.CommercialChannelView> channels() {
        return service.channels();
    }

    @PostMapping("/api/v1/commercial-contact-channels")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('CUSTOMERS_WRITE')")
    public MemberLoyaltyService.CommercialChannelView createChannel(
            @Valid @RequestBody ChannelRequest request) {
        return service.createChannel(request.command());
    }

    @PutMapping("/api/v1/commercial-contact-channels/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('CUSTOMERS_WRITE')")
    public MemberLoyaltyService.CommercialChannelView updateChannel(
            @PathVariable UUID id,
            @Valid @RequestBody ChannelRequest request) {
        return service.updateChannel(id, request.command());
    }

    @GetMapping("/api/v1/member-card-deliveries")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('CUSTOMERS_READ')")
    public List<MemberLoyaltyService.MemberCardDeliveryView> cardDeliveries(
            @RequestParam(required = false) MemberCardDeliveryStatus status) {
        return service.cardDeliveries(status);
    }

    @PatchMapping("/api/v1/member-card-deliveries/{id}/retry")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('CUSTOMERS_WRITE')")
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
            long minPoints,
            @NotNull BigDecimal discountPercent,
            boolean discountEnabled,
            int sortOrder) {

        MemberLoyaltyService.MemberCategoryCommand command() {
            return new MemberLoyaltyService.MemberCategoryCommand(
                    name, minPoints, discountPercent, discountEnabled, sortOrder);
        }
    }

    public record SettingsRequest(
            @NotNull BigDecimal balanceAccrualPercent,
            @NotNull BalanceExpirationPolicy balanceExpirationPolicy,
            @NotNull BigDecimal pointsPerEuro,
            boolean categoryAutoEnabled,
            boolean memberWelcomeEnabled,
            @NotNull MemberCardCodeFormat memberCardCodeFormat,
            String welcomeSubjectTemplate,
            String welcomeBodyTemplate) {

        MemberLoyaltyService.MemberSettingsCommand command() {
            return new MemberLoyaltyService.MemberSettingsCommand(
                    balanceAccrualPercent, balanceExpirationPolicy, pointsPerEuro,
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
