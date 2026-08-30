package com.tpverp.backend.party.loyalty.central;

import com.tpverp.backend.party.MemberBalanceLotType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface MemberBalanceCentralGateway {

    BootstrapResponse bootstrap(BootstrapRequest request);

    Optional<MemberWalletBootstrapStatus> discoverBootstrap(BootstrapStoreRequest request);

    void beginBootstrapSnapshot(
            UUID bootstrapId,
            BootstrapSnapshotBeginRequest request);

    void uploadBootstrapChunk(
            UUID bootstrapId,
            UUID snapshotId,
            BootstrapChunkKind kind,
            int index,
            BootstrapSnapshotChunkRequest request);

    void completeBootstrapSnapshot(
            UUID bootstrapId,
            UUID snapshotId,
            BootstrapSnapshotCompleteRequest request);

    MemberWalletBootstrapStatus bootstrapStatus(
            UUID bootstrapId,
            BootstrapStoreRequest request);

    default ManualPointsAdjustmentResponse adjustPoints(
            ManualPointsAdjustmentRequest request) {
        throw new MemberBalanceCentralException(
                MemberBalanceCentralException.Kind.UNAVAILABLE,
                "El ajuste central de puntos no esta disponible");
    }

    default OfficialPointsFeedResponse officialPointsFeed(
            OfficialPointsFeedRequest request) {
        throw new MemberBalanceCentralException(
                MemberBalanceCentralException.Kind.UNAVAILABLE,
                "El feed oficial de puntos no esta disponible");
    }

    default Optional<PointsBootstrapStatus> discoverPointsBootstrap(
            PointsBootstrapStoreRequest request) {
        throw new MemberBalanceCentralException(
                MemberBalanceCentralException.Kind.UNAVAILABLE,
                "El bootstrap central de puntos no esta disponible");
    }

    default PointsBootstrapStatus beginPointsBootstrapSnapshot(
            UUID bootstrapId,
            PointsBootstrapBeginRequest request) {
        throw new MemberBalanceCentralException(
                MemberBalanceCentralException.Kind.UNAVAILABLE,
                "El bootstrap central de puntos no esta disponible");
    }

    default PointsBootstrapStatus uploadPointsBootstrapChunk(
            UUID bootstrapId,
            UUID snapshotId,
            PointsBootstrapChunkKind kind,
            int index,
            PointsBootstrapChunkRequest request) {
        throw new MemberBalanceCentralException(
                MemberBalanceCentralException.Kind.UNAVAILABLE,
                "El bootstrap central de puntos no esta disponible");
    }

    default PointsBootstrapStatus completePointsBootstrapSnapshot(
            UUID bootstrapId,
            UUID snapshotId,
            PointsBootstrapCompleteRequest request) {
        throw new MemberBalanceCentralException(
                MemberBalanceCentralException.Kind.UNAVAILABLE,
                "El bootstrap central de puntos no esta disponible");
    }

    default PointsBootstrapStatus pointsBootstrapStatus(
            UUID bootstrapId,
            PointsBootstrapStoreRequest request) {
        throw new MemberBalanceCentralException(
                MemberBalanceCentralException.Kind.UNAVAILABLE,
                "El bootstrap central de puntos no esta disponible");
    }

    default PointsOfficialStateChunk pointsOfficialStateChunk(
            UUID bootstrapId,
            int index,
            PointsBootstrapStoreRequest request) {
        throw new MemberBalanceCentralException(
                MemberBalanceCentralException.Kind.UNAVAILABLE,
                "El estado oficial inicial de puntos no esta disponible");
    }

    ReservationResponse reserve(ReserveRequest request);

    ReservationResponse heartbeat(UUID reservationId, ReservationOwnerRequest request);

    ReservationResponse release(UUID reservationId, ReservationOwnerRequest request);

    /** Replaces the active retention snapshot for this reservation. */
    default ReservationResponse configureRetention(
            UUID reservationId,
            ConfigureRetentionRequest request) {
        throw new MemberBalanceCentralException(
                MemberBalanceCentralException.Kind.UNAVAILABLE,
                "La retencion central de saldo no esta disponible");
    }

    ReservationResponse prepare(UUID reservationId, PrepareRequest request);

    ReservationResponse finalizePrepared(UUID reservationId, PreparedOwnerRequest request);

    ReservationResponse abortPrepared(UUID reservationId, PreparedOwnerRequest request);

    record BootstrapRequest(
            UUID companyId,
            UUID storeId,
            Instant snapshotAt,
            String checksum,
            List<BootstrapAccount> accounts) {
    }

    record BootstrapAccount(
            UUID memberId,
            BigDecimal balance,
            BigDecimal points,
            List<BootstrapLot> lots) {
    }

    record BootstrapLot(
            UUID lotId,
            BigDecimal remainingAmount,
            Instant createdAt,
            Instant expiresAt,
            UUID sourceMovementId) {
    }

    record BootstrapResponse(
            String status,
            UUID sourceStoreId,
            UUID sourceInstallationId,
            String checksum,
            Instant snapshotAt,
            int accountCount) {
    }

    record BootstrapStoreRequest(
            UUID companyId,
            UUID storeId) {

        public BootstrapStoreRequest {
            Objects.requireNonNull(companyId, "companyId");
            Objects.requireNonNull(storeId, "storeId");
        }
    }

    record BootstrapSnapshotBeginRequest(
            UUID companyId,
            UUID storeId,
            UUID snapshotId,
            Instant cutoffAt,
            int accountChunkCount,
            int lotChunkCount,
            int accountCount,
            int lotCount,
            String snapshotChecksum) {

        public BootstrapSnapshotBeginRequest {
            Objects.requireNonNull(companyId, "companyId");
            Objects.requireNonNull(storeId, "storeId");
            Objects.requireNonNull(snapshotId, "snapshotId");
            Objects.requireNonNull(cutoffAt, "cutoffAt");
            requireCounts(accountChunkCount, lotChunkCount, accountCount, lotCount);
            requireHash(snapshotChecksum, "snapshotChecksum");
        }
    }

    enum BootstrapChunkKind {
        ACCOUNTS,
        LOTS
    }

    record SnapshotAccount(
            UUID memberId,
            BigDecimal loyaltyBalance,
            BigDecimal returnCreditBalance) {

        public SnapshotAccount {
            Objects.requireNonNull(memberId, "memberId");
            loyaltyBalance = exactMoney(loyaltyBalance, "loyaltyBalance");
            returnCreditBalance = exactMoney(returnCreditBalance, "returnCreditBalance");
            if (loyaltyBalance.signum() < 0 || returnCreditBalance.signum() < 0) {
                throw new IllegalArgumentException("Los saldos del snapshot no pueden ser negativos");
            }
        }
    }

    record SnapshotLot(
            UUID lotId,
            UUID memberId,
            MemberBalanceLotType balanceType,
            BigDecimal originalAmount,
            BigDecimal remainingAmount,
            Instant createdAt,
            Instant expiresAt,
            UUID sourceMovementId,
            UUID documentId) {

        public SnapshotLot {
            Objects.requireNonNull(lotId, "lotId");
            Objects.requireNonNull(memberId, "memberId");
            Objects.requireNonNull(balanceType, "balanceType");
            originalAmount = exactMoney(originalAmount, "originalAmount");
            remainingAmount = exactMoney(remainingAmount, "remainingAmount");
            Objects.requireNonNull(createdAt, "createdAt");
            if (originalAmount.signum() < 0
                    || remainingAmount.signum() < 0
                    || remainingAmount.compareTo(originalAmount) > 0) {
                throw new IllegalArgumentException("Importes de lote invalidos");
            }
        }
    }

    record BootstrapSnapshotChunkRequest(
            UUID companyId,
            UUID storeId,
            String chunkHash,
            List<SnapshotAccount> accounts,
            List<SnapshotLot> lots) {

        public BootstrapSnapshotChunkRequest {
            Objects.requireNonNull(companyId, "companyId");
            Objects.requireNonNull(storeId, "storeId");
            requireHash(chunkHash, "chunkHash");
            accounts = List.copyOf(Objects.requireNonNull(accounts, "accounts"));
            lots = List.copyOf(Objects.requireNonNull(lots, "lots"));
            if (accounts.size() > 500 || lots.size() > 500) {
                throw new IllegalArgumentException("Un chunk no puede superar 500 registros");
            }
        }

        public void validateFor(BootstrapChunkKind kind) {
            Objects.requireNonNull(kind, "kind");
            if (kind == BootstrapChunkKind.ACCOUNTS
                    && (accounts.isEmpty() || !lots.isEmpty())) {
                throw new IllegalArgumentException(
                        "Un chunk ACCOUNTS solo puede contener cuentas");
            }
            if (kind == BootstrapChunkKind.LOTS
                    && (lots.isEmpty() || !accounts.isEmpty())) {
                throw new IllegalArgumentException(
                        "Un chunk LOTS solo puede contener lotes");
            }
        }
    }

    record BootstrapSnapshotCompleteRequest(
            UUID companyId,
            UUID storeId,
            String snapshotChecksum) {

        public BootstrapSnapshotCompleteRequest {
            Objects.requireNonNull(companyId, "companyId");
            Objects.requireNonNull(storeId, "storeId");
            requireHash(snapshotChecksum, "snapshotChecksum");
        }
    }

    enum MemberWalletBootstrapStatusValue {
        COLLECTING,
        RECONCILING,
        CONFLICT,
        COMPLETED,
        CANCELLED
    }

    record MemberWalletBootstrapStatus(
            UUID bootstrapId,
            UUID companyId,
            MemberWalletBootstrapStatusValue status,
            Instant cutoffAt,
            List<UUID> expectedStoreIds,
            List<UUID> completedStoreIds,
            List<UUID> missingStoreIds,
            List<UUID> conflictStoreIds,
            String conflictReason,
            Instant createdAt,
            Instant completedAt) {

        public MemberWalletBootstrapStatus {
            Objects.requireNonNull(bootstrapId, "bootstrapId");
            Objects.requireNonNull(companyId, "companyId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(cutoffAt, "cutoffAt");
            expectedStoreIds = List.copyOf(Objects.requireNonNull(
                    expectedStoreIds, "expectedStoreIds"));
            completedStoreIds = List.copyOf(Objects.requireNonNull(
                    completedStoreIds, "completedStoreIds"));
            missingStoreIds = List.copyOf(Objects.requireNonNull(
                    missingStoreIds, "missingStoreIds"));
            conflictStoreIds = List.copyOf(Objects.requireNonNull(
                    conflictStoreIds, "conflictStoreIds"));
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    record ReserveRequest(
            UUID companyId,
            UUID storeId,
            UUID memberId,
            String terminalId,
            String saleId) {
    }

    record ManualPointsAdjustmentRequest(
            UUID companyId,
            UUID storeId,
            UUID operationId,
            UUID memberId,
            long storeSequence,
            long amount,
            Instant occurredAt) {
        public ManualPointsAdjustmentRequest {
            Objects.requireNonNull(companyId, "companyId");
            Objects.requireNonNull(storeId, "storeId");
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(memberId, "memberId");
            Objects.requireNonNull(occurredAt, "occurredAt");
            if (storeSequence <= 0 || amount == 0) {
                throw new IllegalArgumentException("Ajuste central de puntos invalido");
            }
        }
    }

    record ManualPointsAdjustmentResponse(
            UUID memberId,
            BigDecimal points,
            BigDecimal pointsDebt,
            long officialRevision,
            Instant syncedAt) {
    }

    record OfficialPointsFeedRequest(
            UUID companyId,
            UUID storeId,
            long afterRevision,
            int limit) {
    }

    record OfficialPointsFeedResponse(
            long requestedAfterRevision,
            long nextRevision,
            boolean hasMore,
            List<ManualPointsAdjustmentResponse> accounts) {
    }

    record PointsBootstrapStoreRequest(UUID companyId, UUID storeId) {
    }

    record PointsBootstrapBeginRequest(
            UUID companyId,
            UUID storeId,
            UUID snapshotId,
            Instant cutoffAt,
            int accountChunkCount,
            int absorbedOperationChunkCount,
            int replayOperationChunkCount,
            int accountCount,
            int absorbedOperationCount,
            int replayOperationCount,
            String snapshotChecksum) {
    }

    enum PointsBootstrapChunkKind {
        ACCOUNTS,
        ABSORBED_OPERATIONS,
        REPLAY_OPERATIONS
    }

    record PointsSnapshotAccount(
            UUID memberId,
            BigDecimal points,
            BigDecimal pointsDebt) {
    }

    record PointsSnapshotOperation(
            UUID operationId,
            String contractHash,
            Long sourceSequence) {
    }

    record PointsBootstrapChunkRequest(
            UUID companyId,
            UUID storeId,
            String chunkHash,
            List<PointsSnapshotAccount> accounts,
            List<PointsSnapshotOperation> absorbedOperations,
            List<PointsSnapshotOperation> replayOperations) {
    }

    record PointsBootstrapCompleteRequest(
            UUID companyId,
            UUID storeId,
            String snapshotChecksum) {
    }

    record PointsBootstrapStatus(
            UUID bootstrapId,
            UUID companyId,
            String status,
            Instant cutoffAt,
            List<UUID> expectedStoreIds,
            List<UUID> completedStoreIds,
            List<UUID> missingStoreIds,
            List<UUID> conflictStoreIds,
            String conflictReason,
            Long officialRevision,
            Long centralWatermark,
            Instant createdAt,
            Instant completedAt) {
    }

    record PointsOfficialAccount(
            UUID memberId,
            BigDecimal points,
            BigDecimal pointsDebt) {
    }

    record PointsOfficialStateChunk(
            UUID bootstrapId,
            long revision,
            long centralWatermark,
            int chunkIndex,
            int totalChunks,
            String chunkHash,
            List<PointsOfficialAccount> accounts) {
    }

    record ReservationOwnerRequest(
            UUID companyId,
            UUID storeId,
            String terminalId,
            String saleId) {
    }

    record PrepareRequest(
            UUID companyId,
            UUID storeId,
            String terminalId,
            String saleId,
            UUID operationId,
            BigDecimal loyaltyAmount,
            BigDecimal returnCreditAmount,
            long expectedRetentionRevision,
            String expectedRetentionFingerprint) {

        public PrepareRequest(
                UUID companyId,
                UUID storeId,
                String terminalId,
                String saleId,
                UUID operationId,
                BigDecimal loyaltyAmount,
                BigDecimal returnCreditAmount) {
            this(companyId, storeId, terminalId, saleId, operationId,
                    loyaltyAmount, returnCreditAmount, 0L, "");
        }

        public PrepareRequest {
            Objects.requireNonNull(loyaltyAmount, "loyaltyAmount");
            Objects.requireNonNull(returnCreditAmount, "returnCreditAmount");
            if (loyaltyAmount.signum() < 0 || returnCreditAmount.signum() < 0) {
                throw new IllegalArgumentException("Los importes del monedero no pueden ser negativos");
            }
            if (expectedRetentionRevision < 0) {
                throw new IllegalArgumentException("La revision de retencion no puede ser negativa");
            }
            expectedRetentionFingerprint = expectedRetentionFingerprint == null
                    ? "" : expectedRetentionFingerprint.trim();
        }
    }

    record RetentionClaim(
            UUID lotId,
            UUID sourceMovementId,
            UUID sourceDocumentId,
            BigDecimal amountOriginal,
            BigDecimal amount,
            BigDecimal heldAmount) {
        public RetentionClaim(
                UUID lotId,
                UUID sourceMovementId,
                UUID sourceDocumentId,
                BigDecimal amountOriginal,
                BigDecimal amount) {
            this(lotId, sourceMovementId, sourceDocumentId, amountOriginal, amount, null);
        }

        public RetentionClaim {
            Objects.requireNonNull(lotId, "lotId");
            Objects.requireNonNull(sourceMovementId, "sourceMovementId");
            Objects.requireNonNull(sourceDocumentId, "sourceDocumentId");
            amountOriginal = exactMoney(amountOriginal, "amountOriginal");
            amount = exactMoney(amount, "amount");
            if (amountOriginal.signum() <= 0 || amount.signum() <= 0
                    || amount.compareTo(amountOriginal) > 0) {
                throw new IllegalArgumentException("Claim de retencion invalido");
            }
            if (heldAmount != null) {
                heldAmount = exactMoney(heldAmount, "heldAmount");
                if (heldAmount.signum() < 0 || heldAmount.compareTo(amount) > 0) {
                    throw new IllegalArgumentException("heldAmount de retencion invalido");
                }
            }
        }
    }

    record ConfigureRetentionRequest(
            UUID companyId,
            UUID storeId,
            String terminalId,
            String saleId,
            UUID operationId,
            UUID sourceDocumentId,
            BigDecimal attributedAmount,
            List<RetentionClaim> claims) {
        public ConfigureRetentionRequest {
            Objects.requireNonNull(companyId, "companyId");
            Objects.requireNonNull(storeId, "storeId");
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(sourceDocumentId, "sourceDocumentId");
            attributedAmount = exactMoney(attributedAmount, "attributedAmount");
            if (attributedAmount.signum() < 0) {
                throw new IllegalArgumentException("El saldo atribuido no puede ser negativo");
            }
            claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
            if (claims.stream().anyMatch(claim -> !sourceDocumentId.equals(claim.sourceDocumentId()))) {
                throw new IllegalArgumentException(
                        "Todos los claims deben pertenecer al documento origen");
            }
            if (claims.stream().map(RetentionClaim::amount)
                    .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add)
                    .compareTo(attributedAmount) != 0) {
                throw new IllegalArgumentException("Los claims deben sumar exactamente el saldo atribuido");
            }
        }
    }

    record PreparedOwnerRequest(
            UUID companyId,
            UUID storeId,
            String terminalId,
            String saleId,
            UUID operationId,
            RetentionSnapshot retentionSnapshot) {
        public PreparedOwnerRequest(
                UUID companyId, UUID storeId, String terminalId, String saleId, UUID operationId) {
            this(companyId, storeId, terminalId, saleId, operationId, null);
        }
    }

    record RetentionSnapshot(
            UUID memberId,
            UUID sourceDocumentId,
            UUID returnDocumentId,
            BigDecimal attributedAmount,
            String fingerprint,
            List<RetentionClaim> claims) {
    }

    record ReservedLot(
            MemberBalanceLotType balanceType,
            UUID lotId,
            BigDecimal remainingAmount,
            Instant createdAt,
            Instant expiresAt,
            UUID sourceMovementId,
            UUID documentId) {
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record ReservationResponse(
            UUID reservationId,
            UUID memberId,
            String status,
            BigDecimal reservedLoyaltyAmount,
            BigDecimal reservedReturnCreditAmount,
            BigDecimal preparedLoyaltyAmount,
            BigDecimal preparedReturnCreditAmount,
            UUID prepareOperationId,
            BigDecimal consumedLoyaltyAmount,
            BigDecimal consumedReturnCreditAmount,
            BigDecimal accountLoyaltyBalance,
            BigDecimal accountReturnCreditBalance,
            List<ReservedLot> reservedLots,
            List<RetentionClaim> retentionClaims,
            Instant heartbeatAt,
            Instant leaseExpiresAt,
            int heartbeatIntervalSeconds,
            int leaseSeconds,
            long retentionRevision,
            String retentionFingerprint,
            BigDecimal retentionAttributedAmount,
            BigDecimal heldKnown,
            BigDecimal pendingMissing,
            BigDecimal spentShortfall,
        BigDecimal spendable,
        BigDecimal recoveredKnown) {

        /** Backwards-compatible full constructor for pre-claims callers. */
        public ReservationResponse(
                UUID reservationId, UUID memberId, String status,
                BigDecimal reservedLoyaltyAmount, BigDecimal reservedReturnCreditAmount,
                BigDecimal preparedLoyaltyAmount, BigDecimal preparedReturnCreditAmount,
                UUID prepareOperationId, BigDecimal consumedLoyaltyAmount,
                BigDecimal consumedReturnCreditAmount, BigDecimal accountLoyaltyBalance,
                BigDecimal accountReturnCreditBalance, List<ReservedLot> reservedLots,
                Instant heartbeatAt, Instant leaseExpiresAt, int heartbeatIntervalSeconds,
                int leaseSeconds, long retentionRevision, String retentionFingerprint,
                BigDecimal retentionAttributedAmount, BigDecimal heldKnown,
                BigDecimal pendingMissing, BigDecimal spentShortfall, BigDecimal spendable,
                BigDecimal recoveredKnown) {
            this(reservationId, memberId, status, reservedLoyaltyAmount,
                    reservedReturnCreditAmount, preparedLoyaltyAmount,
                    preparedReturnCreditAmount, prepareOperationId,
                    consumedLoyaltyAmount, consumedReturnCreditAmount,
                    accountLoyaltyBalance, accountReturnCreditBalance,
                    reservedLots, List.of(), heartbeatAt, leaseExpiresAt,
                    heartbeatIntervalSeconds, leaseSeconds, retentionRevision,
                    retentionFingerprint, retentionAttributedAmount, heldKnown,
                    pendingMissing, spentShortfall, spendable, recoveredKnown);
        }

        public ReservationResponse(
                UUID reservationId,
                UUID memberId,
                String status,
                BigDecimal reservedLoyaltyAmount,
                BigDecimal reservedReturnCreditAmount,
                BigDecimal preparedLoyaltyAmount,
                BigDecimal preparedReturnCreditAmount,
                UUID prepareOperationId,
                BigDecimal consumedLoyaltyAmount,
                BigDecimal consumedReturnCreditAmount,
                BigDecimal accountLoyaltyBalance,
                BigDecimal accountReturnCreditBalance,
                List<ReservedLot> reservedLots,
                Instant heartbeatAt,
                Instant leaseExpiresAt,
                int heartbeatIntervalSeconds,
                int leaseSeconds) {
            this(reservationId, memberId, status, reservedLoyaltyAmount,
                    reservedReturnCreditAmount, preparedLoyaltyAmount,
                    preparedReturnCreditAmount, prepareOperationId,
                    consumedLoyaltyAmount, consumedReturnCreditAmount,
                    accountLoyaltyBalance, accountReturnCreditBalance,
                    reservedLots, List.of(), heartbeatAt, leaseExpiresAt,
                    heartbeatIntervalSeconds, leaseSeconds, 0L, "",
                    BigDecimal.ZERO.setScale(2),
                    BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                    BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                    BigDecimal.ZERO.setScale(2));
        }

        public ReservationResponse {
            Objects.requireNonNull(reservedLoyaltyAmount, "reservedLoyaltyAmount");
            Objects.requireNonNull(reservedReturnCreditAmount, "reservedReturnCreditAmount");
            Objects.requireNonNull(preparedLoyaltyAmount, "preparedLoyaltyAmount");
            Objects.requireNonNull(preparedReturnCreditAmount, "preparedReturnCreditAmount");
            Objects.requireNonNull(consumedLoyaltyAmount, "consumedLoyaltyAmount");
            Objects.requireNonNull(consumedReturnCreditAmount, "consumedReturnCreditAmount");
            Objects.requireNonNull(accountLoyaltyBalance, "accountLoyaltyBalance");
            Objects.requireNonNull(accountReturnCreditBalance, "accountReturnCreditBalance");
            retentionFingerprint = retentionFingerprint == null ? "" : retentionFingerprint;
            heldKnown = optionalMoney(heldKnown, "heldKnown");
            pendingMissing = optionalMoney(pendingMissing, "pendingMissing");
            spentShortfall = optionalMoney(spentShortfall, "spentShortfall");
            spendable = spendable == null
                    ? reservedLoyaltyAmount.add(reservedReturnCreditAmount)
                    : exactMoney(spendable, "spendable");
            recoveredKnown = optionalMoney(recoveredKnown, "recoveredKnown");
            retentionAttributedAmount = retentionAttributedAmount == null
                    ? heldKnown.add(pendingMissing).add(spentShortfall).add(recoveredKnown)
                    : exactMoney(retentionAttributedAmount, "retentionAttributedAmount");
            reservedLots = List.copyOf(Objects.requireNonNull(reservedLots, "reservedLots"));
            retentionClaims = retentionClaims == null ? List.of() : List.copyOf(retentionClaims);
        }

        public BigDecimal reservedTotal() {
            return reservedLoyaltyAmount.add(reservedReturnCreditAmount);
        }

        public BigDecimal preparedAmount() {
            return preparedLoyaltyAmount.add(preparedReturnCreditAmount);
        }

        public BigDecimal consumedTotal() {
            return consumedLoyaltyAmount.add(consumedReturnCreditAmount);
        }

        public BigDecimal accountBalance() {
            return accountLoyaltyBalance.add(accountReturnCreditBalance);
        }
    }

    private static BigDecimal exactMoney(BigDecimal value, String field) {
        try {
            return Objects.requireNonNull(value, field)
                    .setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(field + " debe tener escala exacta 2", exception);
        }
    }

    private static BigDecimal optionalMoney(BigDecimal value, String field) {
        return value == null ? BigDecimal.ZERO.setScale(2) : exactMoney(value, field);
    }

    private static void requireCounts(
            int accountChunkCount,
            int lotChunkCount,
            int accountCount,
            int lotCount) {
        if (accountChunkCount < 0 || lotChunkCount < 0
                || accountCount < 0 || lotCount < 0) {
            throw new IllegalArgumentException("Los contadores del snapshot no pueden ser negativos");
        }
        int expectedAccountChunks = accountCount == 0 ? 0 : ((accountCount - 1) / 500) + 1;
        int expectedLotChunks = lotCount == 0 ? 0 : ((lotCount - 1) / 500) + 1;
        if (accountChunkCount != expectedAccountChunks || lotChunkCount != expectedLotChunks) {
            throw new IllegalArgumentException("Los contadores de chunks no coinciden con los registros");
        }
    }

    private static void requireHash(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " debe ser SHA-256 hexadecimal en minusculas");
        }
    }
}
