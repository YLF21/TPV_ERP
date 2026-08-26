package com.tpverp.backend.verifactu;

import java.util.UUID;

/** Validated keyset cursor for the append-only fiscal-event chain. */
record FiscalEventReadCursor(
        long snapshotSequence,
        long anchorSequence,
        UUID anchorId,
        Direction direction,
        String scopeFingerprint) {

    enum Direction {
        NEXT,
        PREVIOUS
    }
}
