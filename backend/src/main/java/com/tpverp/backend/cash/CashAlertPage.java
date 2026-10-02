package com.tpverp.backend.cash;

import java.util.List;

public record CashAlertPage(List<CashAlertView> items, String nextCursor, boolean hasMore, long pendingCount) { }
