package com.tpverp.saas.loyalty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class MemberPointsOfficialApiModels {
    private MemberPointsOfficialApiModels() {
    }

    public record FeedRequest(
            UUID companyId,
            UUID storeId,
            long afterRevision,
            int limit) {
    }

    public record FeedResponse(
            long requestedAfterRevision,
            long nextRevision,
            boolean hasMore,
            List<OfficialAccount> accounts) {
        public FeedResponse {
            accounts = List.copyOf(Objects.requireNonNull(accounts, "accounts"));
        }
    }

    public record ManualAdjustmentRequest(
            UUID companyId,
            UUID storeId,
            UUID operationId,
            UUID memberId,
            long storeSequence,
            long amount,
            Instant occurredAt) {
    }

    public record OfficialAccount(
            UUID memberId,
            BigDecimal points,
            BigDecimal pointsDebt,
            long officialRevision,
            Instant syncedAt) {
    }
}
