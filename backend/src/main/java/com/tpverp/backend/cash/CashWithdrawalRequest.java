package com.tpverp.backend.cash;

import java.math.BigDecimal;
import java.util.List;

public record CashWithdrawalRequest(
        BigDecimal amount,
        String comment,
        List<CashDenominationCommand> denominations) {
}
