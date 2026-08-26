package com.tpverp.backend.verifactu;

import java.util.List;

/** Keyset-paged fiscal history result without a total count. */
public record FiscalHistoryReadCursorPage<T>(
        List<T> items,
        int size,
        String nextCursor,
        String previousCursor,
        boolean hasNext,
        boolean hasPrevious) {

    public FiscalHistoryReadCursorPage {
        items = List.copyOf(items);
    }
}
