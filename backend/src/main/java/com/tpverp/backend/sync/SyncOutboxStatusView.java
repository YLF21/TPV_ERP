package com.tpverp.backend.sync;

public record SyncOutboxStatusView(
        long pending,
        long sending,
        long sent,
        long error) {
}
