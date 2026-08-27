package com.tpverp.backend.party.loyalty.bootstrap;

import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralContextResolver;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralContextResolver.BootstrapContext;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralException;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.BootstrapChunkKind;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.BootstrapSnapshotBeginRequest;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.BootstrapSnapshotChunkRequest;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.BootstrapSnapshotCompleteRequest;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.BootstrapStoreRequest;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.MemberWalletBootstrapStatus;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.MemberWalletBootstrapStatusValue;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class MemberWalletBootstrapWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(MemberWalletBootstrapWorker.class);
    private static final EnumSet<MemberWalletBootstrapSnapshotStatus> ACTIVE_STATUSES = EnumSet.of(
            MemberWalletBootstrapSnapshotStatus.CAPTURED,
            MemberWalletBootstrapSnapshotStatus.UPLOADING,
            MemberWalletBootstrapSnapshotStatus.SUBMITTED);

    private final MemberBalanceCentralContextResolver contexts;
    private final MemberBalanceCentralGateway gateway;
    private final MemberWalletBootstrapCaptureService captureService;
    private final MemberWalletBootstrapSnapshotRepository snapshots;
    private final MemberWalletBootstrapSnapshotAccountRepository accounts;
    private final MemberWalletBootstrapSnapshotLotRepository lots;
    private final MemberWalletBootstrapWorkerStateRepository states;
    private final Clock clock;

    public MemberWalletBootstrapWorker(
            MemberBalanceCentralContextResolver contexts,
            MemberBalanceCentralGateway gateway,
            MemberWalletBootstrapCaptureService captureService,
            MemberWalletBootstrapSnapshotRepository snapshots,
            MemberWalletBootstrapSnapshotAccountRepository accounts,
            MemberWalletBootstrapSnapshotLotRepository lots,
            MemberWalletBootstrapWorkerStateRepository states,
            Clock clock) {
        this.contexts = contexts;
        this.gateway = gateway;
        this.captureService = captureService;
        this.snapshots = snapshots;
        this.accounts = accounts;
        this.lots = lots;
        this.states = states;
        this.clock = clock;
    }

    public void runOnce() {
        List<BootstrapContext> availableContexts;
        try {
            availableContexts = contexts.resolveBootstrapContexts();
        } catch (RuntimeException exception) {
            LOGGER.warn("No se pudieron resolver instalaciones para bootstrap de monedero", exception);
            return;
        }
        for (BootstrapContext context : availableContexts) {
            processSafely(context);
        }
    }

    private void processSafely(BootstrapContext context) {
        Instant now = clock.instant();
        var state = states.findById(context.localStoreId())
                .orElseGet(() -> new MemberWalletBootstrapWorkerState(context, now));
        state.refreshContext(context, now);
        if (!state.isDue(now)) {
            states.save(state);
            return;
        }
        state = states.save(state);
        try {
            process(context, state, now);
        } catch (RuntimeException exception) {
            handleFailure(context, state, exception, now);
        }
    }

    private void process(
            BootstrapContext context,
            MemberWalletBootstrapWorkerState state,
            Instant now) {
        BootstrapStoreRequest storeRequest = new BootstrapStoreRequest(
                context.companyId(), context.storeId());
        Optional<MemberWalletBootstrapSnapshot> activeSnapshot = activeSnapshot(context, state);
        Optional<MemberWalletBootstrapStatus> remoteStatus;
        if (activeSnapshot.isPresent()) {
            remoteStatus = Optional.of(gateway.bootstrapStatus(
                    activeSnapshot.get().getBootstrapId(), storeRequest));
        } else {
            remoteStatus = gateway.discoverBootstrap(storeRequest);
        }
        if (remoteStatus.isEmpty()) {
            state.clearBootstrap(now);
            states.save(state);
            return;
        }

        MemberWalletBootstrapStatus remote = remoteStatus.get();
        validateRemote(remote, context);
        state.trackBootstrap(remote.bootstrapId(), now);
        state = states.save(state);

        var snapshot = snapshots.findByBootstrapIdAndStoreId(
                remote.bootstrapId(), context.storeId()).orElse(null);
        if (remote.status() == MemberWalletBootstrapStatusValue.COMPLETED
                || remote.status() == MemberWalletBootstrapStatusValue.CONFLICT
                || remote.status() == MemberWalletBootstrapStatusValue.CANCELLED) {
            if (snapshot != null) {
                snapshot.applyRemoteStatus(remote, now);
                snapshots.save(snapshot);
            }
            state.clearBootstrap(now);
            states.save(state);
            return;
        }

        if (remote.completedStoreIds().contains(context.storeId())) {
            if (snapshot != null) {
                snapshot.acknowledgeRemoteStoreCompletion(remote, now);
                snapshots.save(snapshot);
            }
            state.recordSuccess(now);
            states.save(state);
            return;
        }

        if (remote.status() != MemberWalletBootstrapStatusValue.COLLECTING
                || !remote.missingStoreIds().contains(context.storeId())) {
            if (snapshot != null) {
                snapshot.applyRemoteStatus(remote, now);
                snapshots.save(snapshot);
            }
            state.recordSuccess(now);
            states.save(state);
            return;
        }

        if (snapshot == null) {
            snapshot = captureService.capture(context, remote.bootstrapId(), remote.cutoffAt());
        }
        if (!snapshot.isDue(now)) {
            state.recordSuccess(now);
            states.save(state);
            return;
        }
        uploadNext(snapshot, context, now);
        state.recordSuccess(now);
        states.save(state);
    }

    private Optional<MemberWalletBootstrapSnapshot> activeSnapshot(
            BootstrapContext context,
            MemberWalletBootstrapWorkerState state) {
        if (state.getActiveBootstrapId() != null) {
            var byId = snapshots.findByBootstrapIdAndStoreId(
                    state.getActiveBootstrapId(), context.storeId());
            if (byId.isPresent() && !byId.get().isTerminal()) {
                return byId;
            }
        }
        return snapshots.findFirstByStoreIdAndStatusInOrderByCreatedAtDesc(
                context.storeId(), ACTIVE_STATUSES);
    }

    private void uploadNext(
            MemberWalletBootstrapSnapshot snapshot,
            BootstrapContext context,
            Instant now) {
        if (!snapshot.isBeginAccepted()) {
            gateway.beginBootstrapSnapshot(
                    snapshot.getBootstrapId(),
                    new BootstrapSnapshotBeginRequest(
                            context.companyId(),
                            context.storeId(),
                            snapshot.getSnapshotId(),
                            snapshot.getCutoffAt(),
                            snapshot.getAccountChunkCount(),
                            snapshot.getLotChunkCount(),
                            snapshot.getAccountCount(),
                            snapshot.getLotCount(),
                            snapshot.getSnapshotChecksum()));
            snapshot.markBeginAccepted(now);
            snapshots.save(snapshot);
            return;
        }

        if (snapshot.getNextAccountChunk() < snapshot.getAccountChunkCount()) {
            int index = snapshot.getNextAccountChunk();
            var chunkAccounts = accounts.findBySnapshot_SnapshotIdOrderByMemberId(
                            snapshot.getSnapshotId(),
                            PageRequest.of(index, MemberWalletBootstrapCanonicalizer.CHUNK_SIZE))
                    .stream().map(MemberWalletBootstrapSnapshotAccount::toContract).toList();
            requireChunk(chunkAccounts, BootstrapChunkKind.ACCOUNTS, index);
            String chunkHash = MemberWalletBootstrapCanonicalizer.accountChunkHash(chunkAccounts);
            gateway.uploadBootstrapChunk(
                    snapshot.getBootstrapId(),
                    snapshot.getSnapshotId(),
                    BootstrapChunkKind.ACCOUNTS,
                    index,
                    new BootstrapSnapshotChunkRequest(
                            context.companyId(),
                            context.storeId(),
                            chunkHash,
                            chunkAccounts,
                            List.of()));
            snapshot.markChunkUploaded(BootstrapChunkKind.ACCOUNTS, index, now);
            snapshots.save(snapshot);
            return;
        }

        if (snapshot.getNextLotChunk() < snapshot.getLotChunkCount()) {
            int index = snapshot.getNextLotChunk();
            var chunkLots = lots.findBySnapshot_SnapshotIdOrderByLotId(
                            snapshot.getSnapshotId(),
                            PageRequest.of(index, MemberWalletBootstrapCanonicalizer.CHUNK_SIZE))
                    .stream().map(MemberWalletBootstrapSnapshotLot::toContract).toList();
            requireChunk(chunkLots, BootstrapChunkKind.LOTS, index);
            String chunkHash = MemberWalletBootstrapCanonicalizer.lotChunkHash(chunkLots);
            gateway.uploadBootstrapChunk(
                    snapshot.getBootstrapId(),
                    snapshot.getSnapshotId(),
                    BootstrapChunkKind.LOTS,
                    index,
                    new BootstrapSnapshotChunkRequest(
                            context.companyId(),
                            context.storeId(),
                            chunkHash,
                            List.of(),
                            chunkLots));
            snapshot.markChunkUploaded(BootstrapChunkKind.LOTS, index, now);
            snapshots.save(snapshot);
            return;
        }

        if (!snapshot.isCompleteSent()) {
            gateway.completeBootstrapSnapshot(
                    snapshot.getBootstrapId(),
                    snapshot.getSnapshotId(),
                    new BootstrapSnapshotCompleteRequest(
                            context.companyId(),
                            context.storeId(),
                            snapshot.getSnapshotChecksum()));
            snapshot.markCompleteSent(now);
            snapshots.save(snapshot);
        }
    }

    private static void validateRemote(
            MemberWalletBootstrapStatus remote,
            BootstrapContext context) {
        if (!remote.companyId().equals(context.companyId())
                || !remote.expectedStoreIds().contains(context.storeId())) {
            throw new MemberBalanceCentralException(
                    MemberBalanceCentralException.Kind.INVALID_RESPONSE,
                    "El bootstrap remoto no pertenece a la empresa y tienda esperadas");
        }
    }

    private static void requireChunk(List<?> rows, BootstrapChunkKind kind, int index) {
        if (rows.isEmpty() || rows.size() > MemberWalletBootstrapCanonicalizer.CHUNK_SIZE) {
            throw new IllegalStateException(
                    "Chunk " + kind.name() + " " + index + " invalido en snapshot local");
        }
    }

    private void handleFailure(
            BootstrapContext context,
            MemberWalletBootstrapWorkerState state,
            RuntimeException exception,
            Instant now) {
        state = states.findById(context.localStoreId()).orElse(state);
        String message = exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
        MemberWalletBootstrapSnapshot snapshot = state.getActiveBootstrapId() == null
                ? null
                : snapshots.findByBootstrapIdAndStoreId(
                        state.getActiveBootstrapId(), context.storeId()).orElse(null);
        boolean conflict = exception instanceof MemberBalanceCentralException central
                && central.getKind() == MemberBalanceCentralException.Kind.CONFLICT;
        if (snapshot != null) {
            if (conflict) {
                snapshot.markConflict(message, now);
            } else {
                snapshot.markFailure(message, now);
            }
            snapshots.save(snapshot);
        }
        if (conflict) {
            state.clearBootstrap(now);
        } else {
            state.recordFailure(message, now);
        }
        states.save(state);
        LOGGER.warn(
                "Bootstrap local de monedero aplazado para tienda SaaS {}: {}",
                context.storeId(),
                message);
    }
}
