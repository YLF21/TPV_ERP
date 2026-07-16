package com.tpverp.backend.shared.api;

import java.util.List;

public record PagedResult<T>(
        List<T> items,
        String nextCursor,
        boolean hasMore) {
}
