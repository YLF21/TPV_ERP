package com.tpverp.backend.cash;

import java.util.List;
public record CashOpeningAlertPage(List<CashOpeningAlertView> items, String nextCursor,
        boolean hasMore, long pendingCount) { }
