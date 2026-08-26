package com.tpverp.backend.verifactu;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/** Versioned URL-safe timestamp/id cursor with a scope fingerprint. */
final class FiscalHistoryReadCursorCodec {
    private static final byte[] MAGIC = "FHC1".getBytes(StandardCharsets.US_ASCII);
    private static final int FINGERPRINT_BYTES = 32;
    private static final int MAX_CURSOR_LENGTH = 256;

    private FiscalHistoryReadCursorCodec() {
    }

    static String encode(FiscalHistoryReadCursor cursor) {
        if (cursor == null || cursor.anchorTimestamp() == null || cursor.anchorId() == null
                || cursor.scopeFingerprint() == null) {
            throw invalid();
        }
        try {
            var output = new ByteArrayOutputStream(96);
            try (var data = new DataOutputStream(output)) {
                data.write(MAGIC);
                data.writeByte(cursor.direction() == FiscalHistoryReadCursor.Direction.NEXT ? 1 : 2);
                data.writeLong(cursor.anchorTimestamp().getEpochSecond());
                data.writeInt(cursor.anchorTimestamp().getNano());
                data.writeLong(cursor.anchorId().getMostSignificantBits());
                data.writeLong(cursor.anchorId().getLeastSignificantBits());
                var fingerprint = HexFormat.of().parseHex(cursor.scopeFingerprint());
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

    static FiscalHistoryReadCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > MAX_CURSOR_LENGTH) {
            throw invalid();
        }
        try {
            var bytes = Base64.getUrlDecoder().decode(encoded);
            try (var data = new DataInputStream(new ByteArrayInputStream(bytes))) {
                if (!MessageDigest.isEqual(data.readNBytes(MAGIC.length), MAGIC)) {
                    throw invalid();
                }
                var direction = switch (data.readUnsignedByte()) {
                    case 1 -> FiscalHistoryReadCursor.Direction.NEXT;
                    case 2 -> FiscalHistoryReadCursor.Direction.PREVIOUS;
                    default -> throw invalid();
                };
                var timestamp = Instant.ofEpochSecond(data.readLong(), data.readInt());
                var id = new UUID(data.readLong(), data.readLong());
                var fingerprint = data.readNBytes(FINGERPRINT_BYTES);
                if (fingerprint.length != FINGERPRINT_BYTES || data.read() != -1) {
                    throw invalid();
                }
                return new FiscalHistoryReadCursor(timestamp, id, direction,
                        HexFormat.of().formatHex(fingerprint));
            }
        } catch (IllegalArgumentException | EOFException | java.time.DateTimeException exception) {
            throw invalid();
        } catch (java.io.IOException exception) {
            throw invalid();
        }
    }

    static String fingerprint(Object... values) {
        return FiscalRecordReadCursorCodec.fingerprint(values);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("cursor no es valido");
    }
}
