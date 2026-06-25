package com.tpverp.backend.cash;

import java.math.BigDecimal;
import java.util.List;

public record CashEntryRequest(
        BigDecimal amount,
        String comment,
        String authorizerUsername,
        String authorizerPassword,
        List<CashDenominationCommand> denominations) {
}
