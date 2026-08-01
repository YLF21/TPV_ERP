package com.tpverp.backend.cash;

import java.time.LocalDate;
import java.util.List;

public record CashClosureFilterOptionsView(
        LocalDate businessDate,
        String timezone,
        List<CashClosureFilterOptionView> terminals,
        List<CashClosureFilterOptionView> users) {
}
