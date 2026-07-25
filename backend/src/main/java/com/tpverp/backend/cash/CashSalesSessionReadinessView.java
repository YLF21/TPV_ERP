package com.tpverp.backend.cash;

public record CashSalesSessionReadinessView(
        boolean cashSessionRequired,
        boolean open,
        CashSessionView session) {
}
