package com.tpverp.backend.cash;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CashAlertAttemptView(UUID id, int attemptNumber, Instant occurredAt, UUID userId,
        String username, String userName, BigDecimal expectedFund, BigDecimal countedFund,
        BigDecimal difference, boolean sessionClosed) { }
