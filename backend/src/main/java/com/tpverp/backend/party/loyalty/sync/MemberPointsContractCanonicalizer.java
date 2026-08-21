package com.tpverp.backend.party.loyalty.sync;

import com.tpverp.backend.party.MemberPointsOperationType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

public final class MemberPointsContractCanonicalizer {
    private MemberPointsContractCanonicalizer() {
    }

    public static String contractHash(
            UUID operationId,
            UUID memberId,
            MemberPointsOperationType operationType,
            long amount,
            UUID sourceDocumentId,
            UUID originalDocumentId,
            Instant occurredAt,
            long localPointsDelta,
            long localDebtDelta) {
        var canonical = "1|"
                + operationId + "|"
                + memberId + "|"
                + operationType.name() + "|"
                + amount + "|"
                + nullable(sourceDocumentId) + "|"
                + nullable(originalDocumentId) + "|"
                + occurredAt + "|"
                + localPointsDelta + "|"
                + localDebtDelta + "\n";
        return sha256(canonical);
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }

    private static String nullable(UUID value) {
        return value == null ? "-" : value.toString();
    }
}
