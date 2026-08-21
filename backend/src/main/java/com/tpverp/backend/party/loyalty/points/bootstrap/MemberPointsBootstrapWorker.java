package com.tpverp.backend.party.loyalty.points.bootstrap;

import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralContextResolver;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.PointsBootstrapBeginRequest;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.PointsBootstrapChunkKind;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.PointsBootstrapChunkRequest;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.PointsBootstrapCompleteRequest;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.PointsBootstrapStatus;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.PointsBootstrapStoreRequest;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.PointsSnapshotAccount;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.PointsSnapshotOperation;
import com.tpverp.backend.party.loyalty.points.MemberPointsProjectionCoordinator;
import com.tpverp.backend.party.loyalty.points.MemberPointsProjectionStateRepository;
import com.tpverp.backend.party.loyalty.points.MemberPointsProjectionStatus;
import com.tpverp.backend.party.loyalty.sync.MemberPointsContractCanonicalizer;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class MemberPointsBootstrapWorker {
    private static final Logger log = LoggerFactory.getLogger(
            MemberPointsBootstrapWorker.class);
    private static final int MAX_CHUNKS_PER_RUN = 8;

    private final MemberBalanceCentralContextResolver contexts;
    private final MemberBalanceCentralGateway central;
    private final MemberPointsProjectionStateRepository states;
    private final MemberPointsProjectionCoordinator coordinator;
    private final MemberPointsBootstrapCaptureService capture;
    private final MemberPointsBootstrapSealService seals;
    private final MemberPointsBootstrapSnapshotRepository snapshots;
    private final MemberPointsBootstrapUploadRepository uploads;
    private final MemberPointsBootstrapSnapshotAccountRepository accounts;
    private final MemberPointsBootstrapSnapshotOperationRepository operations;
    private final MemberPointsBootstrapProgressService progress;

    public MemberPointsBootstrapWorker(
            MemberBalanceCentralContextResolver contexts,
            MemberBalanceCentralGateway central,
            MemberPointsProjectionStateRepository states,
            MemberPointsProjectionCoordinator coordinator,
            MemberPointsBootstrapCaptureService capture,
            MemberPointsBootstrapSealService seals,
            MemberPointsBootstrapSnapshotRepository snapshots,
            MemberPointsBootstrapUploadRepository uploads,
            MemberPointsBootstrapSnapshotAccountRepository accounts,
            MemberPointsBootstrapSnapshotOperationRepository operations,
            MemberPointsBootstrapProgressService progress) {
        this.contexts = contexts;
        this.central = central;
        this.states = states;
        this.coordinator = coordinator;
        this.capture = capture;
        this.seals = seals;
        this.snapshots = snapshots;
        this.uploads = uploads;
        this.accounts = accounts;
        this.operations = operations;
        this.progress = progress;
    }

    public void runOnce() {
        for (var context : contexts.resolveBootstrapContexts()) {
            try {
                runContext(context);
            } catch (RuntimeException exception) {
                log.warn(
                        "No se pudo avanzar el bootstrap de puntos de la tienda {}: {}",
                        context.localStoreId(),
                        exception.getMessage());
            }
        }
    }

    private void runContext(
            MemberBalanceCentralContextResolver.BootstrapContext context) {
        var request = new PointsBootstrapStoreRequest(
                context.companyId(), context.storeId());
        PointsBootstrapStatus remote = central.discoverPointsBootstrap(request)
                .orElse(null);
        if (remote == null) {
            return;
        }
        requireRemoteContext(context, remote);
        var state = states.findById(context.localStoreId()).orElse(null);
        if (state != null
                && state.getStatus() == MemberPointsProjectionStatus.CENTRAL_ACTIVE) {
            return;
        }
        if ("CONFLICT".equals(remote.status()) || "CANCELLED".equals(remote.status())) {
            var upload = uploads.findByBootstrapIdAndStoreId(
                    remote.bootstrapId(), context.localStoreId()).orElse(null);
            if (upload != null) {
                progress.markRemoteStatus(upload.getSnapshotId(), remote.status());
            }
            return;
        }

        MemberPointsBootstrapUpload upload = ensureUpload(context, remote);
        if (upload.getBeginSentAt() == null) {
            central.beginPointsBootstrapSnapshot(
                    remote.bootstrapId(), beginRequest(context, remote, upload));
            progress.markBeginSent(upload.getSnapshotId());
            upload = uploads.findById(upload.getSnapshotId()).orElseThrow();
        }
        upload = uploadChunks(context, upload);
        if (!upload.allUploadChunksSent()) {
            return;
        }
        if (upload.getSubmittedAt() == null) {
            remote = central.completePointsBootstrapSnapshot(
                    upload.getBootstrapId(),
                    upload.getSnapshotId(),
                    new PointsBootstrapCompleteRequest(
                            context.companyId(),
                            context.storeId(),
                            upload.getSnapshotChecksum()));
            progress.markSubmitted(upload.getSnapshotId());
            progress.markRemoteStatus(upload.getSnapshotId(), remote.status());
        } else {
            remote = central.pointsBootstrapStatus(upload.getBootstrapId(), request);
            progress.markRemoteStatus(upload.getSnapshotId(), remote.status());
            if ("CATCHING_UP".equals(remote.status())) {
                remote = central.completePointsBootstrapSnapshot(
                        upload.getBootstrapId(),
                        upload.getSnapshotId(),
                        new PointsBootstrapCompleteRequest(
                                context.companyId(),
                                context.storeId(),
                                upload.getSnapshotChecksum()));
                progress.markRemoteStatus(upload.getSnapshotId(), remote.status());
            }
        }
        if ("COMPLETED".equals(remote.status())) {
            downloadOfficial(context, upload.getSnapshotId(), remote);
        }
    }

    private MemberPointsBootstrapUpload ensureUpload(
            MemberBalanceCentralContextResolver.BootstrapContext context,
            PointsBootstrapStatus remote) {
        var existing = uploads.findByBootstrapIdAndStoreId(
                remote.bootstrapId(), context.localStoreId());
        if (existing.isPresent()) {
            return existing.get();
        }
        var state = states.findById(context.localStoreId()).orElse(null);
        UUID snapshotId;
        if (state == null || state.getStatus() == MemberPointsProjectionStatus.LOCAL_ACTIVE) {
            snapshotId = UUID.randomUUID();
        } else if (state.getStatus() == MemberPointsProjectionStatus.FROZEN
                && remote.bootstrapId().equals(state.getBootstrapId())) {
            snapshotId = state.getSnapshotId();
        } else {
            throw new IllegalStateException(
                    "La tienda ya tiene otro bootstrap de puntos en curso");
        }
        var freeze = coordinator.freeze(
                context.localCompanyId(),
                context.localStoreId(),
                remote.bootstrapId(),
                snapshotId,
                remote.cutoffAt());
        capture.capture(freeze);
        return seals.seal(
                context.localCompanyId(),
                context.localStoreId(),
                remote.bootstrapId(),
                snapshotId);
    }

    private MemberPointsBootstrapUpload uploadChunks(
            MemberBalanceCentralContextResolver.BootstrapContext context,
            MemberPointsBootstrapUpload initial) {
        var upload = initial;
        for (int sent = 0; sent < MAX_CHUNKS_PER_RUN; sent++) {
            var next = nextChunk(upload);
            if (next == null) {
                return upload;
            }
            central.uploadPointsBootstrapChunk(
                    upload.getBootstrapId(),
                    upload.getSnapshotId(),
                    next.kind(),
                    next.index(),
                    next.request(context));
            progress.markChunkSent(upload.getSnapshotId(), next.kind(), next.index());
            upload = uploads.findById(upload.getSnapshotId()).orElseThrow();
        }
        return upload;
    }

    private Chunk nextChunk(MemberPointsBootstrapUpload upload) {
        if (upload.getNextAccountChunk() < upload.getAccountChunkCount()) {
            int index = upload.getNextAccountChunk();
            var rows = accounts.findChunk(
                    upload.getSnapshotId(),
                    PageRequest.of(index, MemberPointsBootstrapCanonicalizer.CHUNK_SIZE))
                    .getContent();
            var values = rows.stream()
                    .map(row -> new MemberPointsBootstrapCanonicalizer.AccountValue(
                            row.getMemberId(), row.getPoints(), row.getPointsDebt()))
                    .toList();
            String hash = MemberPointsContractCanonicalizer.sha256(values.stream()
                    .map(MemberPointsBootstrapCanonicalizer::accountLine)
                    .collect(java.util.stream.Collectors.joining()));
            return Chunk.accounts(index, hash, rows);
        }
        var snapshot = snapshots.findById(upload.getSnapshotId()).orElseThrow();
        if (upload.getNextAbsorbedChunk() < upload.getAbsorbedChunkCount()) {
            int index = upload.getNextAbsorbedChunk();
            var rows = operations.findAbsorbedChunk(
                    upload.getSnapshotId(),
                    snapshot.getCutSequence(),
                    PageRequest.of(index, MemberPointsBootstrapCanonicalizer.CHUNK_SIZE))
                    .getContent();
            String hash = operationChunkHash(rows, false);
            return Chunk.operations(
                    PointsBootstrapChunkKind.ABSORBED_OPERATIONS,
                    index,
                    hash,
                    rows);
        }
        if (upload.getNextReplayChunk() < upload.getReplayChunkCount()) {
            int index = upload.getNextReplayChunk();
            var rows = operations.findReplayChunk(
                    upload.getSnapshotId(),
                    snapshot.getCutSequence(),
                    PageRequest.of(index, MemberPointsBootstrapCanonicalizer.CHUNK_SIZE))
                    .getContent();
            String hash = operationChunkHash(rows, true);
            return Chunk.operations(
                    PointsBootstrapChunkKind.REPLAY_OPERATIONS,
                    index,
                    hash,
                    rows);
        }
        return null;
    }

    private void downloadOfficial(
            MemberBalanceCentralContextResolver.BootstrapContext context,
            UUID snapshotId,
            PointsBootstrapStatus remote) {
        if (remote.officialRevision() == null || remote.centralWatermark() == null) {
            throw new IllegalStateException("El bootstrap completo no contiene revision oficial");
        }
        var upload = uploads.findById(snapshotId).orElseThrow();
        if (upload.officialStateComplete()) {
            progress.activateCentral(
                    context.localCompanyId(),
                    context.localStoreId(),
                    snapshotId);
            return;
        }
        for (int downloaded = 0;
                downloaded < MAX_CHUNKS_PER_RUN && !upload.officialStateComplete();
                downloaded++) {
            var chunk = central.pointsOfficialStateChunk(
                    upload.getBootstrapId(),
                    upload.getNextOfficialChunk(),
                    new PointsBootstrapStoreRequest(
                            context.companyId(), context.storeId()));
            boolean complete = progress.stageOfficialChunk(
                    context.localCompanyId(),
                    context.localStoreId(),
                    snapshotId,
                    chunk);
            upload = uploads.findById(snapshotId).orElseThrow();
            if (complete) {
                progress.activateCentral(
                        context.localCompanyId(),
                        context.localStoreId(),
                        snapshotId);
                return;
            }
        }
    }

    private static PointsBootstrapBeginRequest beginRequest(
            MemberBalanceCentralContextResolver.BootstrapContext context,
            PointsBootstrapStatus remote,
            MemberPointsBootstrapUpload upload) {
        return new PointsBootstrapBeginRequest(
                context.companyId(),
                context.storeId(),
                upload.getSnapshotId(),
                remote.cutoffAt(),
                upload.getAccountChunkCount(),
                upload.getAbsorbedChunkCount(),
                upload.getReplayChunkCount(),
                upload.getAccountCount(),
                upload.getAbsorbedCount(),
                upload.getReplayCount(),
                upload.getSnapshotChecksum());
    }

    private static void requireRemoteContext(
            MemberBalanceCentralContextResolver.BootstrapContext context,
            PointsBootstrapStatus remote) {
        if (!context.companyId().equals(remote.companyId())
                || !remote.expectedStoreIds().contains(context.storeId())) {
            throw new IllegalStateException(
                    "El bootstrap central no incluye esta empresa y tienda");
        }
    }

    private static String operationChunkHash(
            List<MemberPointsBootstrapSnapshotOperation> rows,
            boolean replay) {
        return MemberPointsContractCanonicalizer.sha256(rows.stream()
                .map(row -> new MemberPointsBootstrapCanonicalizer.OperationValue(
                        row.getOperationId(),
                        row.getContractHash(),
                        row.getSourceSequence()))
                .map(replay
                        ? MemberPointsBootstrapCanonicalizer::replayOperationLine
                        : MemberPointsBootstrapCanonicalizer::operationLine)
                .collect(java.util.stream.Collectors.joining()));
    }

    private record Chunk(
            PointsBootstrapChunkKind kind,
            int index,
            String hash,
            List<PointsSnapshotAccount> accountValues,
            List<PointsSnapshotOperation> operationValues) {
        static Chunk accounts(
                int index,
                String hash,
                List<MemberPointsBootstrapSnapshotAccount> rows) {
            return new Chunk(
                    PointsBootstrapChunkKind.ACCOUNTS,
                    index,
                    hash,
                    rows.stream().map(row -> new PointsSnapshotAccount(
                            row.getMemberId(),
                            BigDecimal.valueOf(row.getPoints()),
                            BigDecimal.valueOf(row.getPointsDebt()))).toList(),
                    List.of());
        }

        static Chunk operations(
                PointsBootstrapChunkKind kind,
                int index,
                String hash,
                List<MemberPointsBootstrapSnapshotOperation> rows) {
            return new Chunk(
                    kind,
                    index,
                    hash,
                    List.of(),
                    rows.stream().map(row -> new PointsSnapshotOperation(
                            row.getOperationId(),
                            row.getContractHash(),
                            row.getSourceSequence())).toList());
        }

        PointsBootstrapChunkRequest request(
                MemberBalanceCentralContextResolver.BootstrapContext context) {
            return new PointsBootstrapChunkRequest(
                    context.companyId(),
                    context.storeId(),
                    hash,
                    accountValues,
                    kind == PointsBootstrapChunkKind.ABSORBED_OPERATIONS
                            ? operationValues : List.of(),
                    kind == PointsBootstrapChunkKind.REPLAY_OPERATIONS
                            ? operationValues : List.of());
        }
    }
}
