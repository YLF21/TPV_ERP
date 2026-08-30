package com.tpverp.saas.loyalty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class LoyaltyApiModels {

    private LoyaltyApiModels() {
    }

    public record BootstrapRequest(
            UUID companyId,
            UUID storeId,
            Instant snapshotAt,
            String checksum,
            List<BootstrapAccount> accounts) {
    }

    public record BootstrapAccount(
            UUID memberId,
            BigDecimal balance,
            BigDecimal points,
            List<BootstrapLot> lots) {
    }

    public record BootstrapLot(
            UUID lotId,
            BigDecimal remainingAmount,
            Instant createdAt,
            Instant expiresAt,
            UUID sourceMovementId) {
    }

    public record BootstrapResponse(
            String status,
            UUID sourceStoreId,
            UUID sourceInstallationId,
            String checksum,
            Instant snapshotAt,
            int accountCount) {
    }

    public record BootstrapSourceRequest(UUID storeId) {
    }

    public record BootstrapSourceResponse(
            UUID companyId,
            UUID sourceStoreId,
            boolean completed,
            Instant designatedAt,
            Instant completedAt) {
    }

    public record ReserveRequest(
            UUID companyId,
            UUID storeId,
            UUID memberId,
            String terminalId,
            String saleId,
            BigDecimal attributedAmount,
            List<RetentionClaim> retentionClaims,
            Long retentionRevision,
            String retentionFingerprint) {

        public ReserveRequest(
                UUID companyId,
                UUID storeId,
                UUID memberId,
                String terminalId,
                String saleId) {
            this(companyId, storeId, memberId, terminalId, saleId, null, List.of(), 0L, "");
        }

        public ReserveRequest(
                UUID companyId, UUID storeId, UUID memberId, String terminalId, String saleId,
                List<RetentionClaim> retentionClaims, long retentionRevision, String retentionFingerprint) {
            this(companyId, storeId, memberId, terminalId, saleId, null,
                    retentionClaims, retentionRevision, retentionFingerprint);
        }

        public ReserveRequest {
            retentionRevision = retentionRevision == null ? 0L : retentionRevision;
            retentionClaims = retentionClaims == null ? List.of() : List.copyOf(retentionClaims);
            retentionFingerprint = retentionFingerprint == null ? "" : retentionFingerprint.trim();
            if (retentionRevision < 0 || retentionClaims.stream().anyMatch(claim -> claim == null)) {
                throw new IllegalArgumentException("Retencion de devolucion invalida");
            }
        }
    }

    public record RetentionClaim(
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

        public RetentionClaim(
                UUID lotId,
                UUID sourceMovementId,
                BigDecimal amountOriginal,
                BigDecimal amount) {
            this(lotId, sourceMovementId, null, amountOriginal, amount);
        }

        public RetentionClaim(UUID lotId, UUID sourceMovementId, BigDecimal amount) {
            this(lotId, sourceMovementId, null, amount, amount);
        }
    }

    public record RetentionConfigureRequest(
            UUID companyId,
            UUID storeId,
            String terminalId,
            String saleId,
            BigDecimal attributedAmount,
                List<RetentionClaim> retentionClaims,
            UUID sourceDocumentId) {

        public RetentionConfigureRequest(
                UUID companyId,
                UUID storeId,
                String terminalId,
                String saleId,
                BigDecimal attributedAmount,
                List<RetentionClaim> retentionClaims) {
            this(companyId, storeId, terminalId, saleId, attributedAmount, retentionClaims,
                    inferSourceDocumentId(retentionClaims));
        }

        public RetentionConfigureRequest {
            retentionClaims = retentionClaims == null ? null : List.copyOf(retentionClaims);
        }

        private static UUID inferSourceDocumentId(List<RetentionClaim> claims) {
            if (claims == null || claims.isEmpty()) return null;
            UUID first = claims.getFirst() == null ? null : claims.getFirst().sourceDocumentId();
            return claims.stream().allMatch(value -> value != null && first != null
                    && first.equals(value.sourceDocumentId())) ? first : null;
        }
    }

    public record ReservationOwnerRequest(
            UUID companyId,
            UUID storeId,
            String terminalId,
            String saleId) {
    }

    public record PrepareRequest(
            UUID companyId,
            UUID storeId,
            String terminalId,
            String saleId,
            UUID operationId,
            BigDecimal amount) {
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record PreparedOwnerRequest(
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

    public record RetentionSnapshot(
            UUID memberId,
            UUID sourceDocumentId,
            UUID returnDocumentId,
            BigDecimal attributedAmount,
            String fingerprint,
            List<RetentionClaim> claims) {
    }

    public record ReservationResponse(
            UUID reservationId,
            UUID memberId,
            String status,
            BigDecimal reservedTotal,
            BigDecimal preparedAmount,
            UUID prepareOperationId,
            BigDecimal consumedTotal,
            BigDecimal accountBalance,
            Instant heartbeatAt,
            Instant leaseExpiresAt,
            int heartbeatIntervalSeconds,
            int leaseSeconds) {
    }

    public record WalletPrepareRequest(
            UUID companyId,
            UUID storeId,
            String terminalId,
            String saleId,
            UUID operationId,
            BigDecimal loyaltyAmount,
            BigDecimal returnCreditAmount,
            long expectedRetentionRevision,
            String expectedRetentionFingerprint) {

        public WalletPrepareRequest(
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

        public WalletPrepareRequest {
            expectedRetentionFingerprint = expectedRetentionFingerprint == null
                    ? "" : expectedRetentionFingerprint.trim();
            if (expectedRetentionRevision < 0) {
                throw new IllegalArgumentException("retentionRevision no puede ser negativo");
            }
        }
    }

    public record WalletReservedLot(
            MemberBalanceType balanceType,
            UUID lotId,
            BigDecimal remainingAmount,
            Instant createdAt,
            Instant expiresAt,
            UUID sourceMovementId,
            UUID documentId) {
    }

    public record WalletReservationResponse(
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
            List<WalletReservedLot> reservedLots,
            Instant heartbeatAt,
            Instant leaseExpiresAt,
            int heartbeatIntervalSeconds,
            int leaseSeconds,
            long retentionRevision,
            String retentionFingerprint,
            List<RetentionClaim> retentionClaims,
            BigDecimal heldKnown,
            BigDecimal pendingMissing,
            BigDecimal spentShortfall,
            BigDecimal spendable,
            BigDecimal recoveredKnown) {

        public WalletReservationResponse(
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
                List<WalletReservedLot> reservedLots,
                Instant heartbeatAt,
                Instant leaseExpiresAt,
                int heartbeatIntervalSeconds,
                int leaseSeconds) {
            this(reservationId, memberId, status, reservedLoyaltyAmount, reservedReturnCreditAmount,
                    preparedLoyaltyAmount, preparedReturnCreditAmount, prepareOperationId,
                    consumedLoyaltyAmount, consumedReturnCreditAmount, accountLoyaltyBalance,
                    accountReturnCreditBalance, reservedLots, heartbeatAt, leaseExpiresAt,
                    heartbeatIntervalSeconds, leaseSeconds, 0L, "", List.of(),
                    BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                    BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));
        }

        public WalletReservationResponse(
                UUID reservationId, UUID memberId, String status,
                BigDecimal reservedLoyaltyAmount, BigDecimal reservedReturnCreditAmount,
                BigDecimal preparedLoyaltyAmount, BigDecimal preparedReturnCreditAmount,
                UUID prepareOperationId, BigDecimal consumedLoyaltyAmount,
                BigDecimal consumedReturnCreditAmount, BigDecimal accountLoyaltyBalance,
                BigDecimal accountReturnCreditBalance, List<WalletReservedLot> reservedLots,
                Instant heartbeatAt, Instant leaseExpiresAt, int heartbeatIntervalSeconds,
                int leaseSeconds, long retentionRevision, String retentionFingerprint,
                List<RetentionClaim> retentionClaims) {
            this(reservationId, memberId, status, reservedLoyaltyAmount, reservedReturnCreditAmount,
                    preparedLoyaltyAmount, preparedReturnCreditAmount, prepareOperationId,
                    consumedLoyaltyAmount, consumedReturnCreditAmount, accountLoyaltyBalance,
                    accountReturnCreditBalance, reservedLots, heartbeatAt, leaseExpiresAt,
                    heartbeatIntervalSeconds, leaseSeconds, retentionRevision, retentionFingerprint,
                    retentionClaims, BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                    BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));
        }

        public WalletReservationResponse {
            retentionFingerprint = retentionFingerprint == null ? "" : retentionFingerprint;
            retentionClaims = retentionClaims == null ? List.of() : List.copyOf(retentionClaims);
        }
    }

    public record WalletBootstrapDiscoverRequest(
            UUID companyId,
            UUID storeId) {
    }

    public record BootstrapStoreRequest(
            UUID companyId,
            UUID storeId) {
    }

    public record WalletBootstrapBeginRequest(
            UUID companyId,
            UUID storeId,
            UUID snapshotId,
            Instant cutoffAt,
            int accountChunkCount,
            int lotChunkCount,
            int accountCount,
            int lotCount,
            String snapshotChecksum) {
    }

    public record WalletBootstrapChunkRequest(
            UUID companyId,
            UUID storeId,
            String chunkHash,
            List<SnapshotAccount> accounts,
            List<SnapshotLot> lots) {
    }

    public record WalletBootstrapCompleteRequest(
            UUID companyId,
            UUID storeId,
            String snapshotChecksum) {
    }

    public record SnapshotAccount(
            UUID memberId,
            BigDecimal loyaltyBalance,
            BigDecimal returnCreditBalance) {
    }

    public record SnapshotLot(
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

    public record WalletBootstrapStatus(
            UUID bootstrapId,
            UUID companyId,
            String status,
            Instant cutoffAt,
            List<UUID> expectedStoreIds,
            List<UUID> completedStoreIds,
            List<UUID> missingStoreIds,
            List<UUID> conflictStoreIds,
            String conflictReason,
            Instant createdAt,
            Instant completedAt) {
    }

    public record PointsBootstrapStoreRequest(UUID companyId, UUID storeId) {}

    public record PointsBootstrapBeginRequest(
            UUID companyId, UUID storeId, UUID snapshotId, Instant cutoffAt,
            int accountChunkCount, int absorbedOperationChunkCount, int replayOperationChunkCount,
            int accountCount, int absorbedOperationCount, int replayOperationCount,
            String snapshotChecksum) {}

    public record PointsBootstrapChunkRequest(
            UUID companyId, UUID storeId, String chunkHash,
            List<PointsSnapshotAccount> accounts,
            List<PointsSnapshotOperation> absorbedOperations,
            List<PointsSnapshotOperation> replayOperations) {}

    public record PointsBootstrapCompleteRequest(UUID companyId, UUID storeId, String snapshotChecksum) {}

    public record PointsSnapshotAccount(UUID memberId, BigDecimal points, BigDecimal pointsDebt) {}

    public record PointsSnapshotOperation(UUID operationId, String contractHash, Long sourceSequence) {}

    public record PointsBootstrapStatus(
            UUID bootstrapId, UUID companyId, String status, Instant cutoffAt,
            List<UUID> expectedStoreIds, List<UUID> completedStoreIds,
            List<UUID> missingStoreIds, List<UUID> conflictStoreIds,
            String conflictReason, Long officialRevision, Long centralWatermark,
            Instant createdAt, Instant completedAt) {}

    public record PointsOfficialAccount(UUID memberId, BigDecimal points, BigDecimal pointsDebt) {}

    public record PointsOfficialStateChunk(
            UUID bootstrapId, long revision, long centralWatermark,
            int chunkIndex, int totalChunks, String chunkHash,
            List<PointsOfficialAccount> accounts) {}
}
