package com.tpverp.saas.stores;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.tpverp.saas.license.TaxRegime;
import com.tpverp.saas.license.CommercialProfile;
import com.tpverp.saas.license.StoreBillingPeriod;
import com.fasterxml.jackson.annotation.JsonFormat;

public final class StoreWorkspaceApi {
    private StoreWorkspaceApi() { }

    public record Page<T>(List<T> items, int page, int size, long total, int totalPages) { }
    public record StoreRow(UUID id, UUID companyId, String companyName, String code, String name,
            String internalCode, boolean active, Map<String, String> storeAddress, String timeZoneId,
            long installations, long activeInstallations, Instant lastSyncAt, Instant createdAt,
            TaxRegime taxRegime, boolean taxRegimeLocked,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal servicePrice,
            StoreBillingPeriod billingPeriod, int maxWindows, int maxPda, Instant validUntil,
            CommercialProfile commercialProfile) { }
    public record CreateStore(@NotBlank String code, @NotBlank String name,
            @NotNull Map<String, String> storeAddress, @NotBlank String timeZoneId,
            @NotNull TaxRegime taxRegime,
            @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal servicePrice,
            @NotNull StoreBillingPeriod billingPeriod, @Min(1) int maxWindows, @Min(0) int maxPda,
            @NotNull Instant validUntil, CommercialProfile commercialProfile) {
        public CreateStore(String code, String name, Map<String, String> storeAddress, String timeZoneId,
                TaxRegime taxRegime, BigDecimal servicePrice, StoreBillingPeriod billingPeriod,
                int maxWindows, int maxPda, Instant validUntil) {
            this(code, name, storeAddress, timeZoneId, taxRegime, servicePrice, billingPeriod,
                    maxWindows, maxPda, validUntil, null);
        }
    }
    public record UpdateStore(@NotBlank String name, @NotNull Map<String, String> storeAddress,
            @NotBlank String timeZoneId, @NotNull Boolean active, @NotNull TaxRegime taxRegime,
            @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal servicePrice,
            @NotNull StoreBillingPeriod billingPeriod, @Min(1) int maxWindows, @Min(0) int maxPda,
            Instant validUntil, CommercialProfile commercialProfile) {
        public UpdateStore(String name, Map<String, String> storeAddress, String timeZoneId,
                Boolean active, TaxRegime taxRegime, BigDecimal servicePrice, StoreBillingPeriod billingPeriod,
                int maxWindows, int maxPda, Instant validUntil) {
            this(name, storeAddress, timeZoneId, active, taxRegime, servicePrice, billingPeriod,
                    maxWindows, maxPda, validUntil, null);
        }
    }
    public record UpdateActivity(@NotNull Boolean active) { }
    public record AssignCode(@Pattern(regexp = "[0-9]{7}") String internalCode,
            @NotBlank @Size(max = 500) String reason) { }
    public record StoreLink(UUID id, String code, String name, String internalCode, boolean active) { }
    public record CompanyDebt(String currency, BigDecimal outstanding, BigDecimal overdue) { }
    public record LicenseRow(UUID id, String reference, UUID companyId, String companyName, String taxId,
            String status, Instant validUntil, int maxWindows, int maxPda,
            long activeInstallations, Instant lastValidatedAt, Instant lastSyncAt,
            List<StoreLink> stores, String billingScope, String companyBillingStatus, List<CompanyDebt> companyDebt) { }
    public record CreateLicense(@NotNull UUID storeId) { }
    public record CreatedLicense(UUID id, String reference, UUID companyId, UUID storeId,
            String pairingCode, Instant pairingExpiresAt, Instant serverNow, UUID pairingCodeId) { }
    public record ActivationCodeRow(UUID id, UUID companyId, String companyName, UUID storeId,
            String storeName, String internalCode, String storeCode, UUID licenseId, String reference,
            String pairingCode, Instant pairingExpiresAt) { }
    public record ActivationCodePage(List<ActivationCodeRow> items, int page, int size,
            long total, int totalPages, Instant serverNow) { }
}
