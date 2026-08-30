package com.tpverp.backend.party.loyalty.sync;

import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway;
import com.tpverp.backend.party.loyalty.central.MemberReturnBalanceRetentionPlanner;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Typed, immutable snapshot published when a return needs central balance
 * recovery. Generic member movements must not be used for this operation.
 */
public record MemberReturnBalanceRecoveryCommand(
        UUID operationId,
        UUID companyId,
        UUID storeId,
        UUID terminalId,
        UUID memberId,
        UUID sourceDocumentId,
        UUID returnDocumentId,
        BigDecimal attributedAmount,
        String claimsFingerprint,
        List<MemberBalanceCentralGateway.RetentionClaim> claims,
        ReservationIdentity reservation) {

    public MemberReturnBalanceRecoveryCommand {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(sourceDocumentId, "sourceDocumentId");
        Objects.requireNonNull(returnDocumentId, "returnDocumentId");
        attributedAmount = exactMoney(attributedAmount, "attributedAmount");
        if (attributedAmount.signum() < 0) {
            throw new IllegalArgumentException("attributedAmount no puede ser negativo");
        }
        claimsFingerprint = Objects.requireNonNull(claimsFingerprint, "claimsFingerprint").trim();
        if (claimsFingerprint.isEmpty()) {
            throw new IllegalArgumentException("claimsFingerprint no puede estar vacio");
        }
        claims = Objects.requireNonNull(claims, "claims").stream()
                .sorted(Comparator.comparing(claim -> claim == null || claim.lotId() == null
                        ? "" : claim.lotId().toString()))
                .toList();
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        Set<UUID> lotIds = new HashSet<>();
        for (MemberBalanceCentralGateway.RetentionClaim claim : claims) {
            Objects.requireNonNull(claim, "claim");
            if (!lotIds.add(claim.lotId())) {
                throw new IllegalArgumentException("No puede repetirse lotId en un recovery");
            }
            if (!sourceDocumentId.equals(claim.sourceDocumentId())) {
                throw new IllegalArgumentException("sourceDocumentId del claim no coincide");
            }
            total = total.add(claim.amount());
        }
        if (total.compareTo(attributedAmount) != 0) {
            throw new IllegalArgumentException("claims debe igualar attributedAmount");
        }
        if (!MemberReturnBalanceRetentionPlanner.fingerprint(
                sourceDocumentId, attributedAmount, claims).equals(claimsFingerprint)) {
            throw new IllegalArgumentException("claimsFingerprint no coincide con el contenido");
        }
    }

    private static BigDecimal exactMoney(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    public record ReservationIdentity(UUID centralReservationId, String saleId) {
        public ReservationIdentity {
            Objects.requireNonNull(centralReservationId, "centralReservationId");
            Objects.requireNonNull(saleId, "saleId");
            if (saleId.isBlank()) {
                throw new IllegalArgumentException("saleId no puede estar vacio");
            }
        }
    }
}
