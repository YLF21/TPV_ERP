package com.tpverp.backend.party.loyalty.points.bootstrap;

import com.tpverp.backend.party.MemberPointsOperationRepository;
import com.tpverp.backend.party.loyalty.points.MemberPointsProjectionStateRepository;
import com.tpverp.backend.party.loyalty.points.MemberPointsProjectionStatus;
import com.tpverp.backend.party.loyalty.sync.MemberPointsContractCanonicalizer;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberPointsBootstrapSealService {
    private final MemberPointsProjectionStateRepository states;
    private final MemberPointsBootstrapSnapshotRepository snapshots;
    private final MemberPointsBootstrapSnapshotAccountRepository accounts;
    private final MemberPointsBootstrapSnapshotOperationRepository snapshotOperations;
    private final MemberPointsOperationRepository operations;
    private final MemberPointsBootstrapUploadRepository uploads;
    private final Clock clock;

    public MemberPointsBootstrapSealService(
            MemberPointsProjectionStateRepository states,
            MemberPointsBootstrapSnapshotRepository snapshots,
            MemberPointsBootstrapSnapshotAccountRepository accounts,
            MemberPointsBootstrapSnapshotOperationRepository snapshotOperations,
            MemberPointsOperationRepository operations,
            MemberPointsBootstrapUploadRepository uploads,
            Clock clock) {
        this.states = states;
        this.snapshots = snapshots;
        this.accounts = accounts;
        this.snapshotOperations = snapshotOperations;
        this.operations = operations;
        this.uploads = uploads;
        this.clock = clock;
    }

    @Transactional
    public MemberPointsBootstrapUpload seal(
            UUID companyId,
            UUID storeId,
            UUID bootstrapId,
            UUID snapshotId) {
        var existing = uploads.findForUpdate(snapshotId);
        if (existing.isPresent()) {
            existing.get().requireContext(companyId, storeId);
            return existing.get();
        }
        var state = states.findLockedByStoreId(storeId)
                .orElseThrow(() -> new IllegalStateException(
                        "No existe estado de proyeccion para sellar el bootstrap"));
        state.requireCompany(companyId);
        if (state.getStatus() != MemberPointsProjectionStatus.FROZEN
                || !bootstrapId.equals(state.getBootstrapId())
                || !snapshotId.equals(state.getSnapshotId())) {
            throw new IllegalStateException("El estado local no coincide con el bootstrap");
        }
        var snapshot = snapshots.findById(snapshotId)
                .orElseThrow(() -> new IllegalStateException(
                        "No existe el snapshot local de puntos"));
        long sealSequence = state.getLastSequence();
        List<MemberPointsBootstrapCanonicalizer.OperationValue> replay =
                readReplay(companyId, storeId, snapshot.getCutSequence(), sealSequence);
        snapshotOperations.saveAllAndFlush(replay.stream()
                .map(value -> new MemberPointsBootstrapSnapshotOperation(
                        snapshotId,
                        value.operationId(),
                        value.contractHash(),
                        value.sourceSequence()))
                .toList());

        List<MemberPointsBootstrapCanonicalizer.AccountValue> accountValues =
                readAccounts(snapshotId);
        List<MemberPointsBootstrapCanonicalizer.OperationValue> absorbed =
                readSnapshotOperations(
                        snapshotId, snapshot.getCutSequence(), false);
        List<MemberPointsBootstrapCanonicalizer.OperationValue> replayValues =
                readSnapshotOperations(
                        snapshotId, snapshot.getCutSequence(), true);
        List<String> accountHashes = chunkHashes(accountValues.stream()
                .map(MemberPointsBootstrapCanonicalizer::accountLine).toList());
        List<String> absorbedHashes = chunkHashes(absorbed.stream()
                .map(MemberPointsBootstrapCanonicalizer::operationLine).toList());
        List<String> replayHashes = chunkHashes(replayValues.stream()
                .map(MemberPointsBootstrapCanonicalizer::replayOperationLine).toList());
        String checksum = MemberPointsBootstrapCanonicalizer.snapshotChecksum(
                accountHashes, absorbedHashes, replayHashes);
        return uploads.save(new MemberPointsBootstrapUpload(
                snapshot,
                sealSequence,
                accountHashes.size(),
                absorbedHashes.size(),
                replayHashes.size(),
                Math.toIntExact(accountValues.size()),
                Math.toIntExact(absorbed.size()),
                Math.toIntExact(replayValues.size()),
                checksum,
                clock.instant()));
    }

    private List<MemberPointsBootstrapCanonicalizer.OperationValue> readReplay(
            UUID companyId,
            UUID storeId,
            long cutSequence,
            long sealSequence) {
        var result = new ArrayList<MemberPointsBootstrapCanonicalizer.OperationValue>();
        int page = 0;
        org.springframework.data.domain.Slice<MemberPointsOperationRepository.MemberPointsBootstrapOperationProjection> slice;
        do {
            slice = operations.findBootstrapOperationsRange(
                    companyId,
                    storeId,
                    cutSequence,
                    sealSequence,
                    PageRequest.of(page++, MemberPointsBootstrapCanonicalizer.CHUNK_SIZE));
            slice.forEach(row -> result.add(operationValue(row)));
        } while (slice.hasNext());
        return List.copyOf(result);
    }

    private List<MemberPointsBootstrapCanonicalizer.AccountValue> readAccounts(
            UUID snapshotId) {
        var result = new ArrayList<MemberPointsBootstrapCanonicalizer.AccountValue>();
        int page = 0;
        org.springframework.data.domain.Slice<MemberPointsBootstrapSnapshotAccount> slice;
        do {
            slice = accounts.findChunk(
                    snapshotId,
                    PageRequest.of(page++, MemberPointsBootstrapCanonicalizer.CHUNK_SIZE));
            slice.forEach(row -> result.add(
                    new MemberPointsBootstrapCanonicalizer.AccountValue(
                            row.getMemberId(), row.getPoints(), row.getPointsDebt())));
        } while (slice.hasNext());
        return List.copyOf(result);
    }

    private List<MemberPointsBootstrapCanonicalizer.OperationValue> readSnapshotOperations(
            UUID snapshotId,
            long cutSequence,
            boolean replay) {
        var result = new ArrayList<MemberPointsBootstrapCanonicalizer.OperationValue>();
        int page = 0;
        org.springframework.data.domain.Slice<MemberPointsBootstrapSnapshotOperation> slice;
        do {
            slice = replay
                    ? snapshotOperations.findReplayChunk(
                            snapshotId,
                            cutSequence,
                            PageRequest.of(page++, MemberPointsBootstrapCanonicalizer.CHUNK_SIZE))
                    : snapshotOperations.findAbsorbedChunk(
                            snapshotId,
                            cutSequence,
                            PageRequest.of(page++, MemberPointsBootstrapCanonicalizer.CHUNK_SIZE));
            slice.forEach(row -> result.add(
                    new MemberPointsBootstrapCanonicalizer.OperationValue(
                            row.getOperationId(),
                            row.getContractHash(),
                            row.getSourceSequence())));
        } while (slice.hasNext());
        return List.copyOf(result);
    }

    private static MemberPointsBootstrapCanonicalizer.OperationValue operationValue(
            MemberPointsOperationRepository.MemberPointsBootstrapOperationProjection row) {
        return new MemberPointsBootstrapCanonicalizer.OperationValue(
                row.getOperationId(),
                MemberPointsContractCanonicalizer.contractHash(
                        row.getOperationId(),
                        row.getMemberId(),
                        row.getOperationType(),
                        row.getAmount(),
                        row.getSourceDocumentId(),
                        row.getOriginalDocumentId(),
                        row.getOccurredAt(),
                        row.getLocalPointsDelta(),
                        row.getLocalDebtDelta()),
                row.getStoreSequence());
    }

    private static List<String> chunkHashes(List<String> lines) {
        var hashes = new ArrayList<String>();
        for (int start = 0;
                start < lines.size();
                start += MemberPointsBootstrapCanonicalizer.CHUNK_SIZE) {
            int end = Math.min(
                    start + MemberPointsBootstrapCanonicalizer.CHUNK_SIZE,
                    lines.size());
            hashes.add(MemberPointsContractCanonicalizer.sha256(
                    String.join("", lines.subList(start, end))));
        }
        return List.copyOf(hashes);
    }
}
