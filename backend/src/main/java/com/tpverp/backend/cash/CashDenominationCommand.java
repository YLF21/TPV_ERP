package com.tpverp.backend.cash;

import java.math.BigDecimal;

public record CashDenominationCommand(BigDecimal denomination, int quantity) {
}
