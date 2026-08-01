package com.tpverp.backend.cash;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

public record CashEntryRequest(
        BigDecimal amount,
        String comment,
        String authorizerUsername,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        String authorizerPassword,
        List<CashDenominationCommand> denominations) {

    @Override
    public String toString() {
        return "CashEntryRequest[amount=" + amount
                + ", comment=" + comment
                + ", authorizerUsername=" + authorizerUsername
                + ", authorizerPassword=<redacted>]";
    }
}
