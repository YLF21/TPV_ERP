package com.tpverp.backend.party.loyalty.central;

import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.party.MemberBalanceLot;
import com.tpverp.backend.party.MemberBalanceLotRepository;
import com.tpverp.backend.party.MemberDocumentLoyaltySettlement;
import com.tpverp.backend.party.MemberDocumentLoyaltySettlementRepository;
import com.tpverp.backend.party.MemberMovementType;
import com.tpverp.backend.party.MemberMovementRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Builds the central retention claim without mutating the local wallet. */
@Service
public class MemberReturnBalanceRetentionPlanner {
    private final MemberDocumentLoyaltySettlementRepository settlements;
    private final MemberMovementRepository movements;
    private final MemberBalanceLotRepository lots;

    public MemberReturnBalanceRetentionPlanner(
            MemberDocumentLoyaltySettlementRepository settlements,
            MemberMovementRepository movements,
            MemberBalanceLotRepository lots) {
        this.settlements = settlements;
        this.movements = movements;
        this.lots = lots;
    }

    public Plan plan(
            CommercialDocument source,
            BigDecimal cumulativeRefund,
            BigDecimal cumulativeEligibleRefund) {
        Objects.requireNonNull(source, "source");
        MemberDocumentLoyaltySettlement settlement = settlements.findById(source.getId())
                .orElse(null);
        if (settlement == null) return Plan.none(source.getId());
        var reversal = settlement.planReversal(cumulativeRefund, cumulativeEligibleRefund);
        BigDecimal amount = money(reversal.grantedBalanceDelta());
        var sourceLots = movements.findByDocumentIdOrderByCreatedAtAsc(source.getId()).stream()
                .filter(movement -> movement.getType() == MemberMovementType.ACUMULACION_SALDO)
                .flatMap(movement -> lots.findBySourceMovement_Id(movement.getId()).stream())
                .sorted(Comparator.comparing(MemberBalanceLot::getCreatedAt)
                        .thenComparing(MemberBalanceLot::getId))
                .toList();
        var claims = new java.util.ArrayList<MemberBalanceCentralGateway.RetentionClaim>();
        BigDecimal skip = money(settlement.getReversedBalance()
                .subtract(settlement.getReversedOriginalBalanceDebt()).max(BigDecimal.ZERO));
        BigDecimal remaining = amount;
        for (MemberBalanceLot lot : sourceLots) {
            if (remaining.signum() == 0) break;
            if (!lot.getMember().getId().equals(settlement.getMember().getId())
                    || !source.getId().equals(lot.getDocumentId())
                    || lot.getSourceMovement() == null) {
                throw new IllegalStateException("El lote de fidelizacion no pertenece al documento");
            }
            BigDecimal skipped = skip.min(lot.getAmountOriginal());
            skip = skip.subtract(skipped);
            BigDecimal claimAmount = remaining.min(lot.getAmountOriginal().subtract(skipped));
            if (claimAmount.signum() > 0) {
                claims.add(new MemberBalanceCentralGateway.RetentionClaim(
                        lot.getId(), lot.getSourceMovement().getId(), source.getId(),
                        money(lot.getAmountOriginal()), money(claimAmount)));
                remaining = remaining.subtract(claimAmount);
            }
        }
        if (remaining.signum() != 0) {
            throw new IllegalStateException("El saldo generado no puede asignarse a sus lotes originales");
        }
        return new Plan(source.getId(), settlement.getMember().getId(), amount,
                List.copyOf(claims), fingerprint(source.getId(), amount, claims));
    }

    public static String fingerprint(UUID sourceDocumentId, BigDecimal attributed,
            List<MemberBalanceCentralGateway.RetentionClaim> claims) {
        try {
            String claimText = claims.stream()
                    .sorted(Comparator.comparing(c -> c.lotId().toString()))
                    .map(c -> c.lotId() + "|" + c.sourceMovementId() + "|" + c.sourceDocumentId()
                            + "|" + c.amountOriginal().toPlainString() + "|" + c.amount().toPlainString())
                    .collect(java.util.stream.Collectors.joining("\n"));
            String canonical = money(attributed).toPlainString() + "\n" + claimText;
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo calcular fingerprint de retencion", e);
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return Objects.requireNonNull(value, "amount").setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    public record Plan(UUID sourceDocumentId, UUID memberId, BigDecimal attributedAmount,
            List<MemberBalanceCentralGateway.RetentionClaim> claims, String fingerprint) {
        public Plan {
            claims = List.copyOf(claims);
        }

        public static Plan none(UUID sourceDocumentId) {
            BigDecimal zero = BigDecimal.ZERO.setScale(2);
            return new Plan(sourceDocumentId, null, zero, List.of(),
                    MemberReturnBalanceRetentionPlanner.fingerprint(sourceDocumentId, zero,
                            List.<MemberBalanceCentralGateway.RetentionClaim>of()));
        }
    }
}
