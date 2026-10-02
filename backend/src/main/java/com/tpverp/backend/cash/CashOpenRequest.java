package com.tpverp.backend.cash;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.NotNull;

public record CashOpenRequest(@NotNull UUID terminalId, BigDecimal countedFund,
        List<CashDenominationCommand> denominations) {
    public CashOpenRequest(UUID terminalId) { this(terminalId, null, null); }
}
