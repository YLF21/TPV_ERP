package com.tpverp.saas.sync;

import java.util.List;

/** Cursor page used by the administrative sync endpoints. */
public record AdminSyncPage<T>(
        List<T> items,
        String nextCursor,
        boolean hasMore,
        int size) {
}
