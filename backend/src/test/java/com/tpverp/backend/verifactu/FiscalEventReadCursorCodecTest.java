package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class FiscalEventReadCursorCodecTest {
    @Test
    void roundTripPreservesSnapshotSequenceAndTieBreakerId() {
        var cursor = new FiscalEventReadCursor(500, 250, UUID.randomUUID(),
                FiscalEventReadCursor.Direction.NEXT,
                FiscalEventReadCursorCodec.fingerprint("EVENTS", "company", "store"));

        var encoded = FiscalEventReadCursorCodec.encode(cursor);

        assertThat(FiscalEventReadCursorCodec.decode(encoded)).isEqualTo(cursor);
    }

    @Test
    void rejectsAnchorOutsideSnapshot() {
        assertThatThrownBy(() -> FiscalEventReadCursorCodec.encode(new FiscalEventReadCursor(
                1, 2, UUID.randomUUID(), FiscalEventReadCursor.Direction.NEXT,
                FiscalEventReadCursorCodec.fingerprint("scope"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOversizedOpaqueCursorBeforeDecoding() {
        assertThatThrownBy(() -> FiscalEventReadCursorCodec.decode("x".repeat(257)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
