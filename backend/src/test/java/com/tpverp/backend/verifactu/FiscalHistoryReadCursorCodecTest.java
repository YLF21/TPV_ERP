package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FiscalHistoryReadCursorCodecTest {
    @Test
    void roundTripPreservesTimestampIdDirectionAndFingerprint() {
        var cursor = new FiscalHistoryReadCursor(Instant.parse("2026-08-26T10:11:12.123456Z"),
                UUID.randomUUID(), FiscalHistoryReadCursor.Direction.PREVIOUS,
                FiscalHistoryReadCursorCodec.fingerprint("EXPORTS", "company", "store"));

        var encoded = FiscalHistoryReadCursorCodec.encode(cursor);

        assertThat(encoded).doesNotContain("+").doesNotContain("/").doesNotContain("=");
        assertThat(FiscalHistoryReadCursorCodec.decode(encoded)).isEqualTo(cursor);
    }

    @Test
    void rejectsMalformedCursor() {
        assertThatThrownBy(() -> FiscalHistoryReadCursorCodec.decode("not-a-cursor"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOversizedOpaqueCursorBeforeDecoding() {
        assertThatThrownBy(() -> FiscalHistoryReadCursorCodec.decode("x".repeat(257)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
