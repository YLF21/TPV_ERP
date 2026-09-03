package com.tpverp.backend.management;

import java.util.List;

/** A bounded keyset page used by the management directories. */
public record ManagementPage<T>(
        List<T> items,
        int size,
        String nextCursor,
        boolean hasMore) {

    public ManagementPage {
        items = List.copyOf(items);
    }
}
