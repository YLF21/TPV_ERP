package com.tpverp.saas.fiscal;

import java.util.List;

public record FiscalStatusAdminPage<T>(List<T> items, String nextCursor, boolean hasMore, int size) {
}
