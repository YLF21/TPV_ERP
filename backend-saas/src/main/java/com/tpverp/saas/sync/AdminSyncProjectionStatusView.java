package com.tpverp.saas.sync;

import java.time.Instant;

public record AdminSyncProjectionStatusView(
        long received,
        long projected,
        long ignored,
        long error,
        Instant oldestReceivedAt) {
}
