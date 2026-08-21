package com.tpverp.backend.party.loyalty.bootstrap;

import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.BootstrapChunkKind;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.SnapshotAccount;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.SnapshotLot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

final class MemberWalletBootstrapCanonicalizer {

    static final int CHUNK_SIZE = 500;

    private MemberWalletBootstrapCanonicalizer() {
    }

    static String accountChunkHash(List<SnapshotAccount> accounts) {
        requireLexicographicAccountOrder(accounts);
        StringBuilder canonical = new StringBuilder();
        for (SnapshotAccount account : accounts) {
            canonical.append("A|")
                    .append(account.memberId()).append('|')
                    .append(account.loyaltyBalance().toPlainString()).append('|')
                    .append(account.returnCreditBalance().toPlainString()).append('\n');
        }
        return sha256(canonical.toString());
    }

    static String lotChunkHash(List<SnapshotLot> lots) {
        requireLexicographicLotOrder(lots);
        StringBuilder canonical = new StringBuilder();
        for (SnapshotLot lot : lots) {
            canonical.append("L|")
                    .append(lot.lotId()).append('|')
                    .append(lot.memberId()).append('|')
                    .append(lot.balanceType().name()).append('|')
                    .append(lot.originalAmount().toPlainString()).append('|')
                    .append(lot.remainingAmount().toPlainString()).append('|')
                    .append(lot.createdAt()).append('|')
                    .append(nullable(lot.expiresAt())).append('|')
                    .append(nullable(lot.sourceMovementId())).append('|')
                    .append(nullable(lot.documentId())).append('\n');
        }
        return sha256(canonical.toString());
    }

    static MessageDigest newSnapshotDigest() {
        return newDigest();
    }

    static void appendChunk(
            MessageDigest digest,
            BootstrapChunkKind kind,
            int index,
            String chunkHash) {
        digest.update((kind.name() + "|" + index + "|" + chunkHash + "\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    static String finishSnapshotDigest(MessageDigest digest) {
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String nullable(Object value) {
        return value == null ? "-" : value.toString();
    }

    private static void requireLexicographicAccountOrder(List<SnapshotAccount> accounts) {
        String previous = null;
        for (SnapshotAccount account : accounts) {
            String current = account.memberId().toString();
            if (previous != null && previous.compareTo(current) >= 0) {
                throw new IllegalStateException(
                        "Las cuentas no estan ordenadas por UUID.toString() ascendente");
            }
            previous = current;
        }
    }

    private static void requireLexicographicLotOrder(List<SnapshotLot> lots) {
        String previous = null;
        for (SnapshotLot lot : lots) {
            String current = lot.lotId().toString();
            if (previous != null && previous.compareTo(current) >= 0) {
                throw new IllegalStateException(
                        "Los lotes no estan ordenados por UUID.toString() ascendente");
            }
            previous = current;
        }
    }

    private static String sha256(String value) {
        MessageDigest digest = newDigest();
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }
}
