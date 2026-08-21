package com.tpverp.saas.loyalty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class MemberWalletBootstrapReconciliationService {

    private final SaasMemberBalanceAccountRepository accounts;
    private final SaasMemberBalanceLotRepository lots;
    private final SaasMemberBalanceReservationRepository reservations;
    private final SaasMemberWalletBootstrapStagingAccountRepository stagingAccounts;
    private final SaasMemberWalletBootstrapStagingLotRepository stagingLots;

    public MemberWalletBootstrapReconciliationService(
            SaasMemberBalanceAccountRepository accounts,
            SaasMemberBalanceLotRepository lots,
            SaasMemberBalanceReservationRepository reservations,
            SaasMemberWalletBootstrapStagingAccountRepository stagingAccounts,
            SaasMemberWalletBootstrapStagingLotRepository stagingLots) {
        this.accounts = accounts;
        this.lots = lots;
        this.reservations = reservations;
        this.stagingAccounts = stagingAccounts;
        this.stagingLots = stagingLots;
    }

    public void reconcile(SaasMemberWalletBootstrap bootstrap, Instant now) {
        UUID companyId = bootstrap.getCompanyId();
        accounts.ensureProjectionLock("COMPANY:" + companyId);
        accounts.lockProjectionKey("COMPANY:" + companyId);
        if (reservations.countLiveByCompany(companyId, now) > 0) {
            throw conflict("Existen reservas vivas durante la reconciliacion", Set.of());
        }

        List<SaasMemberBalanceAccount> centralAccounts = accounts.findCompanyAccountsForUpdate(companyId);
        List<SaasMemberBalanceLot> centralLots = lots.findByCompanyIdOrderByIdAsc(companyId);
        List<SaasMemberWalletBootstrapStagingAccount> importedAccounts = stagingAccounts
                .findCompletedByBootstrap(bootstrap.getId());
        List<SaasMemberWalletBootstrapStagingLot> importedLots = stagingLots
                .findCompletedByBootstrap(bootstrap.getId());

        Map<UUID, MergedLot> byLotId = new LinkedHashMap<>();
        Map<UUID, MergedLot> bySourceMovement = new HashMap<>();
        for (SaasMemberBalanceLot lot : centralLots) {
            LotData data = new LotData(
                    lot.getId(),
                    lot.getMemberId(),
                    lot.getBalanceType(),
                    lot.getOriginalAmount(),
                    effectiveRemaining(lot.getRemainingAmount(), lot.getExpiresAt(), bootstrap.getCutoffAt()),
                    lot.getCreatedAt(),
                    lot.getExpiresAt(),
                    lot.getSourceMovementId(),
                    lot.getDocumentId());
            addMerged(byLotId, bySourceMovement, data, lot, Set.of());
        }
        for (SaasMemberWalletBootstrapStagingLot lot : importedLots) {
            LotData data = new LotData(
                    lot.getLotId(),
                    lot.getMemberId(),
                    lot.getBalanceType(),
                    lot.getOriginalAmount(),
                    effectiveRemaining(lot.getRemainingAmount(), lot.getExpiresAt(), bootstrap.getCutoffAt()),
                    lot.getCreatedAt(),
                    lot.getExpiresAt(),
                    lot.getSourceMovementId(),
                    lot.getDocumentId());
            addMerged(
                    byLotId,
                    bySourceMovement,
                    data,
                    null,
                    Set.of(lot.getSnapshot().getStoreId()));
        }

        Map<UUID, SaasMemberBalanceAccount> accountsByMember = new HashMap<>();
        for (SaasMemberBalanceAccount account : centralAccounts) {
            accountsByMember.put(account.getMemberId(), account);
        }
        Set<UUID> allMemberIds = new HashSet<>(accountsByMember.keySet());
        importedAccounts.forEach(value -> allMemberIds.add(value.getMemberId()));
        byLotId.values().forEach(value -> allMemberIds.add(value.data().memberId()));

        for (UUID memberId : allMemberIds) {
            accountsByMember.computeIfAbsent(memberId, ignored -> accounts.save(new SaasMemberBalanceAccount(
                    UUID.randomUUID(),
                    companyId,
                    memberId,
                    BigDecimal.ZERO.setScale(2),
                    BigDecimal.ZERO.setScale(2),
                    BigDecimal.ZERO.setScale(4),
                    now)));
        }

        Map<AccountTypeKey, BigDecimal> totals = new HashMap<>();
        List<SaasMemberBalanceLot> newLots = new ArrayList<>();
        for (MergedLot merged : byLotId.values()) {
            LotData data = merged.data();
            totals.merge(
                    new AccountTypeKey(data.memberId(), data.balanceType()),
                    data.remainingAmount(),
                    BigDecimal::add);
            if (merged.centralLot() == null) {
                newLots.add(new SaasMemberBalanceLot(
                        data.lotId(),
                        accountsByMember.get(data.memberId()),
                        data.balanceType(),
                        data.originalAmount(),
                        data.remainingAmount(),
                        data.createdAt(),
                        data.expiresAt(),
                        data.sourceMovementId(),
                        data.documentId()));
            } else if (isExpiredAt(data.expiresAt(), bootstrap.getCutoffAt())
                    && merged.centralLot().getRemainingAmount().signum() > 0) {
                merged.centralLot().expire();
            }
        }

        for (Map.Entry<UUID, SaasMemberBalanceAccount> entry : accountsByMember.entrySet()) {
            UUID memberId = entry.getKey();
            BigDecimal loyalty = totals.getOrDefault(
                    new AccountTypeKey(memberId, MemberBalanceType.LOYALTY),
                    BigDecimal.ZERO.setScale(2));
            BigDecimal returnCredit = totals.getOrDefault(
                    new AccountTypeKey(memberId, MemberBalanceType.RETURN_CREDIT),
                    BigDecimal.ZERO.setScale(2));
            entry.getValue().replaceBalances(loyalty, returnCredit, now);
        }
        accounts.saveAll(accountsByMember.values());
        lots.saveAll(newLots);
    }

    private void addMerged(
            Map<UUID, MergedLot> byLotId,
            Map<UUID, MergedLot> bySourceMovement,
            LotData candidate,
            SaasMemberBalanceLot centralLot,
            Set<UUID> storeIds) {
        MergedLot existingId = byLotId.get(candidate.lotId());
        if (existingId != null) {
            if (!same(existingId.data(), candidate)) {
                throw conflict(
                        "lotId con datos diferentes: " + candidate.lotId(),
                        union(existingId.storeIds(), storeIds));
            }
            existingId.storeIds().addAll(storeIds);
            return;
        }
        if (candidate.sourceMovementId() != null) {
            MergedLot existingSource = bySourceMovement.get(candidate.sourceMovementId());
            if (existingSource != null) {
                throw conflict(
                        "sourceMovementId identifica lotes diferentes: " + candidate.sourceMovementId(),
                        union(existingSource.storeIds(), storeIds));
            }
        }
        MergedLot merged = new MergedLot(candidate, centralLot, new HashSet<>(storeIds));
        byLotId.put(candidate.lotId(), merged);
        if (candidate.sourceMovementId() != null) {
            bySourceMovement.put(candidate.sourceMovementId(), merged);
        }
    }

    private boolean same(LotData left, LotData right) {
        return left.lotId().equals(right.lotId())
                && left.memberId().equals(right.memberId())
                && left.balanceType() == right.balanceType()
                && left.originalAmount().compareTo(right.originalAmount()) == 0
                && left.remainingAmount().compareTo(right.remainingAmount()) == 0
                && left.createdAt().equals(right.createdAt())
                && Objects.equals(left.expiresAt(), right.expiresAt())
                && Objects.equals(left.sourceMovementId(), right.sourceMovementId())
                && Objects.equals(left.documentId(), right.documentId());
    }

    private BigDecimal effectiveRemaining(BigDecimal remaining, Instant expiresAt, Instant cutoffAt) {
        return isExpiredAt(expiresAt, cutoffAt) ? BigDecimal.ZERO.setScale(2) : remaining;
    }

    private boolean isExpiredAt(Instant expiresAt, Instant cutoffAt) {
        return expiresAt != null && !expiresAt.isAfter(cutoffAt);
    }

    private Set<UUID> union(Set<UUID> left, Set<UUID> right) {
        Set<UUID> values = new HashSet<>(left);
        values.addAll(right);
        return values;
    }

    private MemberWalletBootstrapConflictException conflict(String reason, Set<UUID> storeIds) {
        return new MemberWalletBootstrapConflictException(reason, storeIds);
    }

    private record LotData(
            UUID lotId,
            UUID memberId,
            MemberBalanceType balanceType,
            BigDecimal originalAmount,
            BigDecimal remainingAmount,
            Instant createdAt,
            Instant expiresAt,
            UUID sourceMovementId,
            UUID documentId) {
    }

    private record MergedLot(
            LotData data,
            SaasMemberBalanceLot centralLot,
            Set<UUID> storeIds) {
    }

    private record AccountTypeKey(UUID memberId, MemberBalanceType balanceType) {
    }
}
