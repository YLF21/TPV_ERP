package com.tpverp.saas.admin;

import java.util.List;

public record OutboxFailurePageResponse(
        List<OutboxFailureResponse> items,
        String nextCursor) {

    public OutboxFailurePageResponse {
        items = List.copyOf(items);
    }
}
