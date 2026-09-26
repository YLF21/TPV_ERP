package com.tpverp.saas.supervision;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SupportInterventionModels {
    private SupportInterventionModels() { }
    public record Request(@NotNull UUID requestId, @NotNull @Min(0) Long expectedVersion,
            @NotBlank String expectedTicketStatus, @NotBlank String action,
            @NotBlank @Size(max = 4000) String note,
            @Pattern(regexp = "[0-9]{6,15}|^$") String teamViewerId) { }
    public record Event(UUID requestId, long version, String action, String status, String note,
            String teamViewerId, String actor, Instant createdAt) { }
    public record State(UUID ticketId, UUID companyId, String status, long version, String ticketStatus,
            String teamViewerId, List<Event> events) { }
}