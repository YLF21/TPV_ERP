package com.tpverp.backend.party.loyalty.bootstrap;

import com.tpverp.backend.party.MemberBalanceLotRepository;
import com.tpverp.backend.party.MemberRepository;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralContextResolver.BootstrapContext;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.BootstrapChunkKind;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.SnapshotAccount;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.SnapshotLot;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberWalletBootstrapCaptureService {

    private final MemberRepository members;
    private final MemberBalanceLotRepository liveLots;
    private final MemberWalletBootstrapSnapshotRepository snapshots;
    private final MemberWalletBootstrapSnapshotAccountRepository snapshotAccounts;
    private final MemberWalletBootstrapSnapshotLotRepository snapshotLots;
    private final EntityManager entityManager;
    private final Clock clock;

    public MemberWalletBootstrapCaptureService(
            MemberRepository members,
            MemberBalanceLotRepository liveLots,
            MemberWalletBootstrapSnapshotRepository snapshots,
            MemberWalletBootstrapSnapshotAccountRepository snapshotAccounts,
            MemberWalletBootstrapSnapshotLotRepository snapshotLots,
            EntityManager entityManager,
            Clock clock) {
        this.members = members;
        this.liveLots = liveLots;
        this.snapshots = snapshots;
        this.snapshotAccounts = snapshotAccounts;
        this.snapshotLots = snapshotLots;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public MemberWalletBootstrapSnapshot capture(
            BootstrapContext context,
            UUID bootstrapId,
            Instant cutoffAt) {
        var existing = snapshots.findByBootstrapIdAndStoreId(bootstrapId, context.storeId());
        if (existing.isPresent()) {
            return existing.get();
        }

        Instant now = clock.instant();
        var snapshot = new MemberWalletBootstrapSnapshot(
                bootstrapId,
                context.localCompanyId(),
                context.localStoreId(),
                context.companyId(),
                context.storeId(),
                cutoffAt,
                now);
        snapshots.saveAndFlush(snapshot);

        var snapshotDigest = MemberWalletBootstrapCanonicalizer.newSnapshotDigest();
        CaptureCount accountCount = captureAccounts(
                snapshot.getSnapshotId(), context.localCompanyId(), snapshotDigest);
        CaptureCount lotCount = captureLots(
                snapshot.getSnapshotId(), context.localCompanyId(), snapshotDigest);

        entityManager.flush();
        entityManager.clear();
        var completed = snapshots.findById(snapshot.getSnapshotId())
                .orElseThrow(() -> new IllegalStateException("El snapshot capturado no existe"));
        completed.finishCapture(
                accountCount.chunks(),
                lotCount.chunks(),
                accountCount.records(),
                lotCount.records(),
                MemberWalletBootstrapCanonicalizer.finishSnapshotDigest(snapshotDigest));
        return completed;
    }

    private CaptureCount captureAccounts(
            UUID snapshotId,
            UUID companyId,
            java.security.MessageDigest snapshotDigest) {
        int page = 0;
        int chunks = 0;
        int records = 0;
        while (true) {
            var slice = members.findWalletSnapshotAccounts(
                    companyId,
                    PageRequest.of(page, MemberWalletBootstrapCanonicalizer.CHUNK_SIZE));
            if (slice.isEmpty()) {
                break;
            }
            List<SnapshotAccount> contracts = slice.getContent().stream()
                    .map(row -> new SnapshotAccount(
                            row.getMemberId(),
                            row.getLoyaltyBalance(),
                            row.getReturnCreditBalance()))
                    .toList();
            var snapshot = entityManager.getReference(
                    MemberWalletBootstrapSnapshot.class, snapshotId);
            snapshotAccounts.saveAll(contracts.stream()
                    .map(account -> new MemberWalletBootstrapSnapshotAccount(snapshot, account))
                    .toList());
            snapshotAccounts.flush();
            String chunkHash = MemberWalletBootstrapCanonicalizer.accountChunkHash(contracts);
            MemberWalletBootstrapCanonicalizer.appendChunk(
                    snapshotDigest, BootstrapChunkKind.ACCOUNTS, chunks, chunkHash);
            chunks++;
            records += contracts.size();
            boolean hasNext = slice.hasNext();
            entityManager.clear();
            if (!hasNext) {
                break;
            }
            page++;
        }
        return new CaptureCount(chunks, records);
    }

    private CaptureCount captureLots(
            UUID snapshotId,
            UUID companyId,
            java.security.MessageDigest snapshotDigest) {
        int page = 0;
        int chunks = 0;
        int records = 0;
        while (true) {
            var slice = liveLots.findWalletSnapshotLots(
                    companyId,
                    PageRequest.of(page, MemberWalletBootstrapCanonicalizer.CHUNK_SIZE));
            if (slice.isEmpty()) {
                break;
            }
            List<SnapshotLot> contracts = slice.getContent().stream()
                    .map(row -> new SnapshotLot(
                            row.getLotId(),
                            row.getMemberId(),
                            row.getBalanceType(),
                            row.getOriginalAmount(),
                            row.getRemainingAmount(),
                            row.getCreatedAt(),
                            row.getExpiresAt(),
                            row.getSourceMovementId(),
                            row.getDocumentId()))
                    .toList();
            var snapshot = entityManager.getReference(
                    MemberWalletBootstrapSnapshot.class, snapshotId);
            snapshotLots.saveAll(contracts.stream()
                    .map(lot -> new MemberWalletBootstrapSnapshotLot(snapshot, lot))
                    .toList());
            snapshotLots.flush();
            String chunkHash = MemberWalletBootstrapCanonicalizer.lotChunkHash(contracts);
            MemberWalletBootstrapCanonicalizer.appendChunk(
                    snapshotDigest, BootstrapChunkKind.LOTS, chunks, chunkHash);
            chunks++;
            records += contracts.size();
            boolean hasNext = slice.hasNext();
            entityManager.clear();
            if (!hasNext) {
                break;
            }
            page++;
        }
        return new CaptureCount(chunks, records);
    }

    private record CaptureCount(int chunks, int records) {
    }
}
