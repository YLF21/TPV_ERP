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
            @Pattern(regexp = "[0-9]{6,15}|^$") String teamViewerId,
            @Size(max = 240) String assignee, Instant visitAt,
            @Size(max = 4000) String resolutionSummary, @Size(max = 4000) String verificationNotes,
            @Size(max = 240) String confirmedBy, UUID assigneeUserId, Instant nextReviewAt) {
        public Request(UUID requestId, Long expectedVersion, String expectedTicketStatus, String action, String note, String teamViewerId,
                String assignee, Instant visitAt, String resolutionSummary, String verificationNotes, String confirmedBy) {
            this(requestId,expectedVersion,expectedTicketStatus,action,note,teamViewerId,assignee,visitAt,resolutionSummary,verificationNotes,confirmedBy,null,null);
        }
        public Request(UUID requestId, Long expectedVersion, String expectedTicketStatus, String action, String note, String teamViewerId) {
            this(requestId, expectedVersion, expectedTicketStatus, action, note, teamViewerId, null, null, null, null, null);
        }
    }
    public record Event(UUID requestId, long version, String action, String status, String note,
            String teamViewerId, String actor, Instant createdAt, String assignee, Instant visitAt,
            String resolutionSummary, String verificationNotes, String confirmedBy, UUID assigneeUserId, Instant nextReviewAt, String resumeStatus) { }
    public record State(UUID ticketId, UUID companyId, String status, long version, String ticketStatus,
            String teamViewerId, List<Event> events, String assignee, Instant visitAt,
            String resolutionSummary, String verificationNotes, String confirmedBy, FailureLink failure, UUID assigneeUserId, Instant nextReviewAt, String resumeStatus, List<Assignee> assignees) { }
    public record Assignee(UUID id, String username) { }
    public record FailureLink(String key, String status, String code, String storeName, Instant receivedAt) { }
}