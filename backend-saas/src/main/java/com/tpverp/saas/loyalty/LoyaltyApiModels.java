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
            String saleId) {
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

    public record PreparedOwnerRequest(
            UUID companyId,
            UUID storeId,
            String terminalId,
            String saleId,
            UUID operationId) {
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
            BigDecimal returnCreditAmount) {
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
            int leaseSeconds) {
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
