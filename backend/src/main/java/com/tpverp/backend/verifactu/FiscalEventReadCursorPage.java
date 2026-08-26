package com.tpverp.backend.verifactu;

import java.util.List;

/** Keyset-paged fiscal-event result; it deliberately has no total count. */
public record FiscalEventReadCursorPage(
        List<FiscalEventView> items,
        int size,
        String nextCursor,
        String previousCursor,
        boolean hasNext,
        boolean hasPrevious,
        long snapshotSequence) {

    public FiscalEventReadCursorPage {
        items = List.copyOf(items);
    }
}
