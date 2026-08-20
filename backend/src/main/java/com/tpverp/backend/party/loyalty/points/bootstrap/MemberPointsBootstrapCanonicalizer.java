package com.tpverp.backend.party.loyalty.points.bootstrap;

import com.tpverp.backend.party.loyalty.sync.MemberPointsContractCanonicalizer;
import java.util.List;

public final class MemberPointsBootstrapCanonicalizer {
    public static final int CHUNK_SIZE = 500;

    private MemberPointsBootstrapCanonicalizer() {
    }

    public static String accountLine(AccountValue value) {
        return "A|" + value.memberId() + "|" + value.points()
                + "|" + value.pointsDebt() + "\n";
    }

    public static String operationLine(OperationValue value) {
        return "I|" + value.operationId() + "|" + value.contractHash()
                + "|" + value.sourceSequence() + "\n";
    }

    public static String replayOperationLine(OperationValue value) {
        return "R|" + value.operationId() + "|" + value.contractHash()
                + "|" + value.sourceSequence() + "\n";
    }

    public static String snapshotChecksum(
            List<String> accountChunkHashes,
            List<String> operationChunkHashes) {
        var manifest = new StringBuilder();
        for (int index = 0; index < accountChunkHashes.size(); index++) {
            manifest.append("ACCOUNTS|").append(index).append('|')
                    .append(accountChunkHashes.get(index)).append('\n');
        }
        for (int index = 0; index < operationChunkHashes.size(); index++) {
            manifest.append("OPERATIONS|").append(index).append('|')
                    .append(operationChunkHashes.get(index)).append('\n');
        }
        return MemberPointsContractCanonicalizer.sha256(manifest.toString());
    }

    public static String snapshotChecksum(
            List<String> accountChunkHashes,
            List<String> absorbedOperationChunkHashes,
            List<String> replayOperationChunkHashes) {
        var manifest = new StringBuilder();
        appendManifest(manifest, "ACCOUNTS", accountChunkHashes);
        appendManifest(
                manifest,
                "ABSORBED_OPERATIONS",
                absorbedOperationChunkHashes);
        appendManifest(
                manifest,
                "REPLAY_OPERATIONS",
                replayOperationChunkHashes);
        return MemberPointsContractCanonicalizer.sha256(manifest.toString());
    }

    private static void appendManifest(
            StringBuilder manifest,
            String kind,
            List<String> hashes) {
        for (int index = 0; index < hashes.size(); index++) {
            manifest.append(kind).append('|').append(index).append('|')
                    .append(hashes.get(index)).append('\n');
        }
    }

    public record AccountValue(java.util.UUID memberId, long points, long pointsDebt) {
    }

    public record OperationValue(
            java.util.UUID operationId, String contractHash, long sourceSequence) {
    }
}
