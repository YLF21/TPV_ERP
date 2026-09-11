package com.tpverp.backend.document;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Bounded, manual DEV commands. No snapshot or business values are accepted for writing. */
public final class DocumentSyncRecoveryApi {
    private DocumentSyncRecoveryApi() { }
    public record Scope(@NotNull UUID companyId, @NotNull UUID storeId, @NotNull LocalDate dateFrom,
            @NotNull LocalDate dateTo, Instant createdBefore) { }
    public record PreviewRequest(@NotNull @Valid Scope scope, UUID afterId) { }
    public record PrepareRequest(@NotNull @Valid Scope scope,
            @NotNull @Size(min = 1, max = 100) List<@NotNull UUID> documentIds,
            @NotBlank @Size(max = 500) String reason) { }
    public record Receipt(@NotNull UUID documentId, @NotNull UUID eventId,
            @NotNull @Pattern(regexp = "[1-9][0-9]{0,18}") String sourceRevision, String outboxStatus) { }
    public record VerifyRequest(@NotNull @Valid Scope scope,
            @NotNull @Size(min = 1, max = 100) List<@Valid Receipt> documents) { }
    public record Row(UUID documentId, String number, String type, String status, LocalDate date,
            String total, String currency, String problem) { }
    public record Preview(Scope scope, List<Row> documents, UUID nextAfterId, boolean hasMore) { }
    public record Prepared(Scope scope, List<Receipt> documents, String status) { }
    public record VerifiedRow(UUID documentId, UUID eventId, String sourceRevision, boolean projected,
            Boolean customerLinked, String status) { }
    public record Verified(Scope scope, boolean complete, List<VerifiedRow> documents) { }
    public record Expectation(UUID documentId, UUID eventId, Long sourceRevision) { }
    public record RemoteRow(UUID documentId, String status, Long currentRevision, UUID currentEventId,
            String requestedEventStatus, boolean requestedRevisionRecorded, Boolean customerLinked,
            String total, String currency) { }
}
