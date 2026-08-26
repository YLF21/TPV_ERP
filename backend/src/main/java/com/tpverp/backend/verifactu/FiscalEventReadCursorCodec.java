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
import java.util.UUID;

/** Versioned URL-safe cursor for event sequence/id keyset navigation. */
final class FiscalEventReadCursorCodec {
    private static final byte[] MAGIC = "FEC1".getBytes(StandardCharsets.US_ASCII);
    private static final int FINGERPRINT_BYTES = 32;
    private static final int MAX_CURSOR_LENGTH = 256;

    private FiscalEventReadCursorCodec() {
    }

    static String encode(FiscalEventReadCursor cursor) {
        if (cursor == null || cursor.anchorId() == null || cursor.scopeFingerprint() == null
                || cursor.snapshotSequence() < 1 || cursor.anchorSequence() < 1
                || cursor.anchorSequence() > cursor.snapshotSequence()) {
            throw invalid();
        }
        try {
            var output = new ByteArrayOutputStream(96);
            try (var data = new DataOutputStream(output)) {
                data.write(MAGIC);
                data.writeByte(cursor.direction() == FiscalEventReadCursor.Direction.NEXT ? 1 : 2);
                data.writeLong(cursor.snapshotSequence());
                data.writeLong(cursor.anchorSequence());
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
            throw new IllegalStateException("No se pudo codificar el cursor de eventos", exception);
        }
    }

    static FiscalEventReadCursor decode(String encoded) {
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
                    case 1 -> FiscalEventReadCursor.Direction.NEXT;
                    case 2 -> FiscalEventReadCursor.Direction.PREVIOUS;
                    default -> throw invalid();
                };
                var snapshot = data.readLong();
                var sequence = data.readLong();
                var id = new UUID(data.readLong(), data.readLong());
                var fingerprint = data.readNBytes(FINGERPRINT_BYTES);
                if (snapshot < 1 || sequence < 1 || sequence > snapshot
                        || fingerprint.length != FINGERPRINT_BYTES || data.read() != -1) {
                    throw invalid();
                }
                return new FiscalEventReadCursor(snapshot, sequence, id, direction,
                        HexFormat.of().formatHex(fingerprint));
            }
        } catch (IllegalArgumentException | EOFException exception) {
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
