package com.tpverp.backend.cash;

import java.time.Instant;
import java.util.List;

public record CashCurrentBalancesView(
        Instant asOf,
        String timezone,
        List<CashCurrentBalanceView> terminals) {
}
