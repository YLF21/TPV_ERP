package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class FiscalRecordReadCursorCodecTest {

    @Test
    void roundTripIsUrlSafeAndPreservesDirectionAndSnapshot() {
        var cursor = new FiscalRecordReadCursor(
                500L, 250L, FiscalRecordReadCursor.Direction.PREVIOUS,
                FiscalRecordReadCursorCodec.fingerprint("company", "store", "filters"));

        var encoded = FiscalRecordReadCursorCodec.encode(cursor);
        var decoded = FiscalRecordReadCursorCodec.decode(encoded);

        assertThat(encoded).doesNotContain("+").doesNotContain("/").doesNotContain("=");
        assertThat(decoded).isEqualTo(cursor);
    }

    @Test
    void rejectsMalformedAndOutOfRangeCursor() {
        assertThatThrownBy(() -> FiscalRecordReadCursorCodec.decode("not-a-cursor"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FiscalRecordReadCursorCodec.encode(new FiscalRecordReadCursor(
                0L, 1L, FiscalRecordReadCursor.Direction.NEXT, "bad")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsStructurallyManipulatedCursorWhoseAnchorExceedsSnapshot() {
        var cursor = new FiscalRecordReadCursor(
                500L, 250L, FiscalRecordReadCursor.Direction.NEXT,
                FiscalRecordReadCursorCodec.fingerprint("company", "store", "filters"));
        var bytes = Base64.getUrlDecoder().decode(FiscalRecordReadCursorCodec.encode(cursor));
        ByteBuffer.wrap(bytes).putLong(13, 501L);
        var manipulated = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        assertThatThrownBy(() -> FiscalRecordReadCursorCodec.decode(manipulated))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
