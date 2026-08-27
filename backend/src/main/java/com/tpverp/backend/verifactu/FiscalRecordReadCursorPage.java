package com.tpverp.backend.verifactu;

import java.util.List;

/** Keyset-paged fiscal-record result. It deliberately has no total count. */
public record FiscalRecordReadCursorPage(
        List<FiscalRecordReadView> items,
        int size,
        String nextCursor,
        String previousCursor,
        boolean hasNext,
        boolean hasPrevious,
        long snapshotSequence) {

    public FiscalRecordReadCursorPage {
        items = List.copyOf(items);
    }
}
