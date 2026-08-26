package com.tpverp.backend.verifactu;

import java.time.Instant;
import java.util.UUID;

/** Timestamp/id keyset cursor shared by immutable fiscal-history tables. */
record FiscalHistoryReadCursor(
        Instant anchorTimestamp,
        UUID anchorId,
        Direction direction,
        String scopeFingerprint) {

    enum Direction {
        NEXT,
        PREVIOUS
    }
}
