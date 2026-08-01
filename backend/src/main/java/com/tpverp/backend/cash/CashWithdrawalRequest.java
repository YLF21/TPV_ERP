package com.tpverp.backend.cash;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

public record CashWithdrawalRequest(
        BigDecimal amount,
        String comment,
        List<CashDenominationCommand> denominations,
        boolean withdrawal,
        String authorizerUsername,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        String authorizerPassword) {

    public CashWithdrawalRequest(
            BigDecimal amount,
            String comment,
            List<CashDenominationCommand> denominations) {
        this(amount, comment, denominations, false, null, null);
    }

    public CashWithdrawalRequest(
            BigDecimal amount,
            String comment,
            List<CashDenominationCommand> denominations,
            boolean withdrawal) {
        this(amount, comment, denominations, withdrawal, null, null);
    }

    @Override
    public String toString() {
        return "CashWithdrawalRequest[amount=" + amount
                + ", comment=" + comment
                + ", withdrawal=" + withdrawal
                + ", authorizerUsername=" + authorizerUsername
                + ", authorizerPassword=<redacted>]";
    }
}
