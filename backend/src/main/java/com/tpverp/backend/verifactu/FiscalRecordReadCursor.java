package com.tpverp.backend.verifactu;

/** Validated keyset cursor for the fiscal-record catalogue. */
record FiscalRecordReadCursor(
        long snapshotSequence,
        long anchorSequence,
        Direction direction,
        String filterFingerprint) {

    enum Direction {
        NEXT,
        PREVIOUS
    }
}
