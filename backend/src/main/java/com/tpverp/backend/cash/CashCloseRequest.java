package com.tpverp.backend.cash;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CashCloseRequest(
        BigDecimal retainedFund,
        List<CashDenominationCommand> retainedFundDenominations,
        BigDecimal finalWithdrawalAmount,
        String finalWithdrawalComment,
        List<CashDenominationCommand> finalWithdrawalDenominations,
        UUID closeOperationId,
        UUID reconciliationAttemptId,
        String authorizerUsername,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        String authorizerPassword) {

    public CashCloseRequest(
            BigDecimal retainedFund,
            List<CashDenominationCommand> retainedFundDenominations,
            BigDecimal finalWithdrawalAmount,
            String finalWithdrawalComment,
            List<CashDenominationCommand> finalWithdrawalDenominations) {
        this(
                retainedFund,
                retainedFundDenominations,
                finalWithdrawalAmount,
                finalWithdrawalComment,
                finalWithdrawalDenominations,
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null);
    }

    public CashCloseRequest(
            BigDecimal retainedFund,
            List<CashDenominationCommand> retainedFundDenominations,
            BigDecimal finalWithdrawalAmount,
            String finalWithdrawalComment,
            List<CashDenominationCommand> finalWithdrawalDenominations,
            String authorizerUsername,
            String authorizerPassword) {
        this(
                retainedFund,
                retainedFundDenominations,
                finalWithdrawalAmount,
                finalWithdrawalComment,
                finalWithdrawalDenominations,
                UUID.randomUUID(),
                UUID.randomUUID(),
                authorizerUsername,
                authorizerPassword);
    }

    public CashCloseRequest(
            BigDecimal retainedFund,
            List<CashDenominationCommand> retainedFundDenominations,
            BigDecimal finalWithdrawalAmount,
            String finalWithdrawalComment,
            List<CashDenominationCommand> finalWithdrawalDenominations,
            UUID closeOperationId,
            String authorizerUsername,
            String authorizerPassword) {
        this(
                retainedFund,
                retainedFundDenominations,
                finalWithdrawalAmount,
                finalWithdrawalComment,
                finalWithdrawalDenominations,
                closeOperationId,
                UUID.randomUUID(),
                authorizerUsername,
                authorizerPassword);
    }

    @Override
    public String toString() {
        return "CashCloseRequest[retainedFund=" + retainedFund
                + ", finalWithdrawalAmount=" + finalWithdrawalAmount
                + ", closeOperationId=" + closeOperationId
                + ", reconciliationAttemptId=" + reconciliationAttemptId
                + ", authorizerUsername=" + authorizerUsername
                + ", authorizerPassword=<redacted>]";
    }
}
