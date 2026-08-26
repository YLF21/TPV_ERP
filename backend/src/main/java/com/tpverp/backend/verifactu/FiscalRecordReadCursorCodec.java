package com.tpverp.backend.verifactu;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

/** Versioned URL-safe binary cursor without tenant data in clear text.
 *
 * <p>The fingerprint prevents accidental reuse with different filters. It is not an
 * authorization boundary; the request scope is always resolved server-side.</p>
 */
final class FiscalRecordReadCursorCodec {

    private static final byte[] MAGIC = "FRC1".getBytes(StandardCharsets.US_ASCII);
    private static final int FINGERPRINT_BYTES = 32;
    private static final int MAX_CURSOR_LENGTH = 256;

    private FiscalRecordReadCursorCodec() {
    }

    static String encode(FiscalRecordReadCursor cursor) {
        try {
            var output = new ByteArrayOutputStream(64);
            try (var data = new DataOutputStream(output)) {
                data.write(MAGIC);
                data.writeByte(cursor.direction() == FiscalRecordReadCursor.Direction.NEXT ? 1 : 2);
                data.writeLong(cursor.snapshotSequence());
                data.writeLong(cursor.anchorSequence());
                var fingerprint = HexFormat.of().parseHex(cursor.filterFingerprint());
                if (fingerprint.length != FINGERPRINT_BYTES) {
                    throw invalid();
                }
                data.write(fingerprint);
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(output.toByteArray());
        } catch (IllegalArgumentException exception) {
            throw invalid();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("No se pudo codificar el cursor fiscal", exception);
        }
    }

    static FiscalRecordReadCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > MAX_CURSOR_LENGTH) {
            throw invalid();
        }
        try {
            var bytes = Base64.getUrlDecoder().decode(encoded);
            try (var data = new DataInputStream(new ByteArrayInputStream(bytes))) {
                var magic = data.readNBytes(MAGIC.length);
                if (!MessageDigest.isEqual(magic, MAGIC)) {
                    throw invalid();
                }
                var direction = switch (data.readUnsignedByte()) {
                    case 1 -> FiscalRecordReadCursor.Direction.NEXT;
                    case 2 -> FiscalRecordReadCursor.Direction.PREVIOUS;
                    default -> throw invalid();
                };
                var snapshotSequence = data.readLong();
                var anchorSequence = data.readLong();
                var fingerprint = data.readNBytes(FINGERPRINT_BYTES);
                if (snapshotSequence < 1 || anchorSequence < 1
                        || anchorSequence > snapshotSequence
                        || fingerprint.length != FINGERPRINT_BYTES || data.read() != -1) {
                    throw invalid();
                }
                return new FiscalRecordReadCursor(snapshotSequence, anchorSequence, direction,
                        HexFormat.of().formatHex(fingerprint));
            }
        } catch (IllegalArgumentException | EOFException exception) {
            throw invalid();
        } catch (java.io.IOException exception) {
            throw invalid();
        }
    }

    static String fingerprint(Object... values) {
        var canonical = java.util.Arrays.stream(values)
                .map(value -> value == null ? "" : value.toString())
                .map(value -> value.length() + ":" + value)
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("cursor no es valido");
    }
}
