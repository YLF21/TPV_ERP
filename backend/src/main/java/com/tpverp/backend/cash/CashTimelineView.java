package com.tpverp.backend.cash;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CashTimelineView(LocalDate businessDate, String timezone, List<Item> items) {
    public record Item(String id, Instant occurredAt, UUID userId, String username, String userName,
            String action, String concept, BigDecimal amount, BigDecimal balance,
            String reference, UUID sessionId, CashSessionStatus cashState, String sourceReference) { }
}
