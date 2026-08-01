package com.tpverp.backend.cash;

import java.util.UUID;

public record CashClosureFilterOptionView(
        UUID id,
        String name,
        String secondaryName) {
}
