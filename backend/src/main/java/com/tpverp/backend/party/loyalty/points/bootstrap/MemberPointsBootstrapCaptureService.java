package com.tpverp.backend.party.loyalty.points.bootstrap;

import com.tpverp.backend.party.MemberPointsOperationRepository;
import com.tpverp.backend.party.MemberRepository;
import com.tpverp.backend.party.loyalty.points.MemberPointsProjectionCoordinator.FreezeResult;
import com.tpverp.backend.party.loyalty.points.MemberPointsProjectionStateRepository;
import com.tpverp.backend.party.loyalty.points.MemberPointsProjectionStatus;
import com.tpverp.backend.party.loyalty.sync.MemberPointsContractCanonicalizer;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberPointsBootstrapCaptureService {
    private final MemberRepository members;
    private final MemberPointsOperationRepository operations;
    private final MemberPointsProjectionStateRepository states;
    private final MemberPointsBootstrapSnapshotRepository snapshots;
    private final MemberPointsBootstrapSnapshotAccountRepository snapshotAccounts;
    private final MemberPointsBootstrapSnapshotOperationRepository snapshotOperations;
    private final Clock clock;

    public MemberPointsBootstrapCaptureService(
            MemberRepository members,
            MemberPointsOperationRepository operations,
            MemberPointsProjectionStateRepository states,
            MemberPointsBootstrapSnapshotRepository snapshots,
            MemberPointsBootstrapSnapshotAccountRepository snapshotAccounts,
            MemberPointsBootstrapSnapshotOperationRepository snapshotOperations,
            Clock clock) {
        this.members = members;
        this.operations = operations;
        this.states = states;
        this.snapshots = snapshots;
        this.snapshotAccounts = snapshotAccounts;
        this.snapshotOperations = snapshotOperations;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public CaptureResult capture(FreezeResult freeze) {
        var existing = snapshots.findById(freeze.snapshotId());
        if (existing.isPresent()) {
            return CaptureResult.from(existing.get());
        }
        if (snapshots.findByBootstrapIdAndStoreId(
                freeze.bootstrapId(), freeze.storeId()).isPresent()) {
            throw new IllegalStateException(
                    "El bootstrap ya tiene otro snapshot local inmutable");
        }
        var state = states.findById(freeze.storeId())
                .orElseThrow(() -> new IllegalStateException(
                        "No existe el estado congelado de puntos"));
        if (state.getStatus() != MemberPointsProjectionStatus.FROZEN
                || !freeze.bootstrapId().equals(state.getBootstrapId())
                || !freeze.snapshotId().equals(state.getSnapshotId())
                || freeze.cutSequence() != state.getCutSequence()) {
            throw new IllegalStateException("El corte de puntos ya no coincide");
        }

        var accountValues = readAccounts(freeze.companyId());
        var operationValues = readOperations(freeze);
        var accountHashes = chunkHashes(accountValues.stream()
                .map(MemberPointsBootstrapCanonicalizer::accountLine).toList());
        var operationHashes = chunkHashes(operationValues.stream()
                .map(MemberPointsBootstrapCanonicalizer::operationLine).toList());
        var checksum = MemberPointsBootstrapCanonicalizer.snapshotChecksum(
                accountHashes, operationHashes);
        var snapshot = snapshots.saveAndFlush(new MemberPointsBootstrapSnapshot(
                freeze.snapshotId(), freeze.bootstrapId(), freeze.companyId(),
                freeze.storeId(), freeze.cutoffAt(), freeze.cutSequence(),
                accountHashes.size(), operationHashes.size(),
                accountValues.size(), operationValues.size(), checksum, clock.instant()));
        snapshotAccounts.saveAll(accountValues.stream()
                .map(value -> new MemberPointsBootstrapSnapshotAccount(
                        freeze.snapshotId(), value.memberId(),
                        value.points(), value.pointsDebt()))
                .toList());
        snapshotOperations.saveAll(operationValues.stream()
                .map(value -> new MemberPointsBootstrapSnapshotOperation(
                        freeze.snapshotId(), value.operationId(),
                        value.contractHash(), value.sourceSequence()))
                .toList());
        return CaptureResult.from(snapshot);
    }

    private List<MemberPointsBootstrapCanonicalizer.AccountValue> readAccounts(
            UUID companyId) {
        var result = new ArrayList<MemberPointsBootstrapCanonicalizer.AccountValue>();
        int page = 0;
        org.springframework.data.domain.Slice<MemberRepository.MemberPointsSnapshotAccountProjection> slice;
        do {
            slice = members.findPointsSnapshotAccounts(
                    companyId,
                    PageRequest.of(page++, MemberPointsBootstrapCanonicalizer.CHUNK_SIZE));
            slice.forEach(row -> result.add(
                    new MemberPointsBootstrapCanonicalizer.AccountValue(
                            row.getMemberId(), row.getPoints(), row.getPointsDebt())));
        } while (slice.hasNext());
        return List.copyOf(result);
    }

    private List<MemberPointsBootstrapCanonicalizer.OperationValue> readOperations(
            FreezeResult freeze) {
        var result = new ArrayList<MemberPointsBootstrapCanonicalizer.OperationValue>();
        int page = 0;
        org.springframework.data.domain.Slice<MemberPointsOperationRepository.MemberPointsBootstrapOperationProjection> slice;
        do {
            slice = operations.findBootstrapOperations(
                    freeze.companyId(), freeze.storeId(), freeze.cutSequence(),
                    PageRequest.of(page++, MemberPointsBootstrapCanonicalizer.CHUNK_SIZE));
            slice.forEach(row -> result.add(
                    new MemberPointsBootstrapCanonicalizer.OperationValue(
                            row.getOperationId(),
                            MemberPointsContractCanonicalizer.contractHash(
                                    row.getOperationId(), row.getMemberId(),
                                    row.getOperationType(), row.getAmount(),
                                    row.getSourceDocumentId(), row.getOriginalDocumentId(),
                                    row.getOccurredAt(), row.getLocalPointsDelta(),
                                    row.getLocalDebtDelta()),
                            row.getStoreSequence())));
        } while (slice.hasNext());
        return List.copyOf(result);
    }

    private static List<String> chunkHashes(List<String> lines) {
        var hashes = new ArrayList<String>();
        for (int start = 0; start < lines.size();
                start += MemberPointsBootstrapCanonicalizer.CHUNK_SIZE) {
            int end = Math.min(start + MemberPointsBootstrapCanonicalizer.CHUNK_SIZE,
                    lines.size());
            hashes.add(MemberPointsContractCanonicalizer.sha256(
                    String.join("", lines.subList(start, end))));
        }
        return List.copyOf(hashes);
    }

    public record CaptureResult(
            UUID snapshotId, UUID bootstrapId, UUID companyId, UUID storeId,
            java.time.Instant cutoffAt, long cutSequence,
            int accountChunkCount, int operationChunkCount,
            long accountCount, long operationCount, String snapshotChecksum) {
        private static CaptureResult from(MemberPointsBootstrapSnapshot snapshot) {
            return new CaptureResult(
                    snapshot.getSnapshotId(), snapshot.getBootstrapId(),
                    snapshot.getCompanyId(), snapshot.getStoreId(),
                    snapshot.getCutoffAt(), snapshot.getCutSequence(),
                    snapshot.getAccountChunkCount(), snapshot.getOperationChunkCount(),
                    snapshot.getAccountCount(), snapshot.getOperationCount(),
                    snapshot.getSnapshotChecksum());
        }
    }
}
