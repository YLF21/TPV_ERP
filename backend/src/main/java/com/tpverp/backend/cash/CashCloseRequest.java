package com.tpverp.backend.cash;

import java.math.BigDecimal;
import java.util.List;

public record CashCloseRequest(
        BigDecimal retainedFund,
        List<CashDenominationCommand> retainedFundDenominations,
        BigDecimal finalWithdrawalAmount,
        String finalWithdrawalComment,
        List<CashDenominationCommand> finalWithdrawalDenominations) {
}
