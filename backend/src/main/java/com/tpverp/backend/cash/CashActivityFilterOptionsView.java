package com.tpverp.backend.cash;

import java.time.LocalDate;
import java.util.List;

public record CashActivityFilterOptionsView(LocalDate businessDate, String timezone, LocalDate earliestDate,
        List<CashClosureFilterOptionView> terminals, List<CashClosureFilterOptionView> users) {
}
