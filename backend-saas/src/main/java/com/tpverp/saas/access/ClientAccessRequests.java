package com.tpverp.saas.access;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ClientAccessRequests {
    private ClientAccessRequests() {}

    public enum Status { PENDING, APPROVED, REJECTED }
    public record Page<T>(List<T> items, long total) {}
    public record Summary(UUID id, String username, String fullName, String email, Status status,
            long version, Instant createdAt, Instant reviewedAt, String reviewedBy) {}
    public record Detail(@JsonUnwrapped Summary summary, String taxId, String phone, String address,
            String postalCode, String city, String province, String country, List<String> requestedStores,
            String notes, String reviewReason, UUID tenantUserId, Delivery delivery) {}
    public record Delivery(String status, int attempts, String lastError, Instant nextAttemptAt, Instant deliveredAt) {}
    public record DirectoryUser(UUID id, String username, String fullName, String email,
            boolean active, Instant createdAt, long companyCount, long storeCount) {}
    public record Grant(@NotNull UUID companyId, @NotEmpty @Size(max = 2000) Set<@NotNull UUID> storeIds) {}
    public record Approval(@NotNull @PositiveOrZero Long version,
            @NotNull @Size(max = 100) List<@NotNull @Valid Grant> grants, @Size(max = 500) String reason) {}
    public record Rejection(@NotNull @PositiveOrZero Long version, @NotBlank @Size(max = 500) String reason) {}
}
