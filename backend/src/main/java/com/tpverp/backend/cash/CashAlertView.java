package com.tpverp.backend.cash;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CashAlertView(UUID id, UUID sessionId, UUID terminalId, String terminalName,
        UUID userId, String username, String userName, String type, Instant occurredAt,
        Integer attemptNumber, Boolean sessionClosed, BigDecimal expectedFund, BigDecimal countedFund,
        BigDecimal difference, String status, UUID reviewerId, String reviewerUsername,
        String reviewerName, Instant reviewedAt, String comment, long version) { }
