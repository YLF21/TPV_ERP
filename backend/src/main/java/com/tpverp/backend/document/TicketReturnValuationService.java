package com.tpverp.backend.document;

import com.tpverp.backend.catalog.DiscountType;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.party.MemberDocumentLoyaltyLine;
import com.tpverp.backend.party.MemberDocumentLoyaltyLineRepository;
import com.tpverp.backend.party.MemberDocumentLoyaltySettlementRepository;
import com.tpverp.backend.promotion.Promotion;
import com.tpverp.backend.promotion.PromotionEngine;
import com.tpverp.backend.promotion.PromotionEvaluationLine;
import com.tpverp.backend.promotion.PromotionEvaluationRequest;
import com.tpverp.backend.promotion.PromotionPreview;
import com.tpverp.backend.promotion.PromotionRepository;
import com.tpverp.backend.promotion.PromotionTargetRepository;
import com.tpverp.backend.promotion.PromotionalCoupon;
import com.tpverp.backend.promotion.PromotionalCouponBenefitType;
import com.tpverp.backend.promotion.PromotionalCouponRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Values a partial return against the historical commercial conditions of the
 * original ticket. A promotion is never averaged across units: the basket that
 * remains with the customer is repriced using the exact promotion versions that
 * were applied to the original ticket.
 */
@Service
public class TicketReturnValuationService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final CommercialDocumentRepository documents;
    private final ProductRepository products;
    private final MemberDocumentLoyaltyLineRepository loyaltyLines;
    private final MemberDocumentLoyaltySettlementRepository loyaltySettlements;
    private final PromotionRepository promotions;
    private final PromotionTargetRepository promotionTargets;
    private final PromotionalCouponRepository coupons;
    private final PromotionEngine promotionEngine;

    public TicketReturnValuationService(
            CommercialDocumentRepository documents,
            ProductRepository products,
            MemberDocumentLoyaltyLineRepository loyaltyLines,
            MemberDocumentLoyaltySettlementRepository loyaltySettlements,
            PromotionRepository promotions,
            PromotionTargetRepository promotionTargets,
            PromotionalCouponRepository coupons,
            PromotionEngine promotionEngine) {
        this.documents = documents;
        this.products = products;
        this.loyaltyLines = loyaltyLines;
        this.loyaltySettlements = loyaltySettlements;
        this.promotions = promotions;
        this.promotionTargets = promotionTargets;
        this.coupons = coupons;
        this.promotionEngine = promotionEngine;
    }

    @Transactional(readOnly = true)
    public Valuation value(
            CommercialDocument original,
            Map<UUID, BigDecimal> selectedQuantities) {
        Objects.requireNonNull(original, "original");
        if (original.getTipo() != CommercialDocumentType.TICKET) {
            throw new IllegalArgumentException(
                    "Solo se pueden valorar devoluciones de tickets");
        }
        var selected = canonicalSelection(selectedQuantities);
        if (selected.isEmpty()) {
            throw new IllegalArgumentException(
                    "Selecciona al menos una linea para devolver");
        }
        var sourceLines = original.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.PRODUCT)
                .collect(Collectors.toMap(DocumentLine::getId, Function.identity()));
        if (!sourceLines.keySet().containsAll(selected.keySet())) {
            throw new IllegalArgumentException(
                    "La seleccion contiene lineas ajenas al ticket original");
        }

        var remaining = new LinkedHashMap<DocumentLine, BigDecimal>();
        var selectedGross = Money.euros(BigDecimal.ZERO);
        var selectedByLine = new LinkedHashMap<DocumentLine, BigDecimal>();
        var globalFactor = BigDecimal.ONE.subtract(
                original.getDescuentoGlobal().movePointLeft(2));
        for (var source : sourceLines.values().stream()
                .sorted(java.util.Comparator.comparingInt(DocumentLine::getPosicion))
                .toList()) {
            var purchased = source.getCantidad().abs();
            var previouslyReturned = quantity(
                    documents.confirmedRefundedQuantity(source.getId()));
            var requested = selected.getOrDefault(
                    source.getId(), BigDecimal.ZERO.setScale(3, Money.ROUNDING));
            var available = purchased.subtract(previouslyReturned);
            if (requested.compareTo(available) > 0) {
                throw new IllegalArgumentException(
                        "La cantidad supera el saldo reembolsable de la linea "
                                + source.getCodigo());
            }
            var after = available.subtract(requested);
            if (after.signum() > 0) {
                remaining.put(source, after);
            }
            if (requested.signum() > 0) {
                selectedByLine.put(source, requested);
                selectedGross = Money.euros(selectedGross.add(
                        payableUnitAmount(source, globalFactor).multiply(requested)));
            }
        }

        var remainingBasket = remainingBasketValue(original, remaining, globalFactor);
        var remainingValue = remainingBasket.total();
        var cumulativeRefund = Money.euros(original.getTotal().subtract(remainingValue))
                .max(Money.euros(BigDecimal.ZERO));
        var previouslyRefunded = Money.euros(
                documents.confirmedReturnAmount(original.getId()));
        var currentRefund = Money.euros(cumulativeRefund.subtract(previouslyRefunded));
        if (currentRefund.signum() < 0) {
            throw new IllegalStateException(
                    "Las devoluciones confirmadas superan el valor historico recalculado");
        }
        if (currentRefund.compareTo(selectedGross) > 0) {
            throw new IllegalStateException(
                    "El reembolso calculado supera el valor de los articulos seleccionados");
        }
        var eligibleSelectedGross = eligibleSelectedGross(
                original, selectedByLine, globalFactor);
        var eligibleRefund = selectedGross.signum() == 0
                ? Money.euros(BigDecimal.ZERO)
                : Money.euros(currentRefund.multiply(eligibleSelectedGross)
                        .divide(selectedGross, Money.SCALE + 4, Money.ROUNDING))
                        .min(currentRefund);
        var previouslyReversedEligible = loyaltySettlements.findById(original.getId())
                .map(settlement -> settlement.getReversedEligibleAmount())
                .orElse(Money.euros(BigDecimal.ZERO));
        var cumulativeEligibleRefund = Money.euros(
                previouslyReversedEligible.add(eligibleRefund));
        var selectedTaxTotals = selectedTaxTotals(selectedByLine, globalFactor);
        var originalTaxTotals = documentTaxTotals(original, globalFactor);
        var cumulativeTaxRefunds = subtractTaxTotals(
                originalTaxTotals, remainingBasket.taxTotals());
        var previousTaxRefunds = confirmedTaxRefunds(original.getId());
        var currentTaxRefunds = subtractTaxTotals(
                cumulativeTaxRefunds, previousTaxRefunds);
        reconcileTaxTotal(currentTaxRefunds, currentRefund, selectedTaxTotals.keySet());
        var adjustments = returnAdjustments(selectedTaxTotals, currentTaxRefunds);
        var adjustmentTotal = Money.euros(adjustments.stream()
                .map(TaxAdjustment::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        if (adjustmentTotal.compareTo(
                Money.euros(selectedGross.subtract(currentRefund))) != 0) {
            throw new IllegalStateException(
                    "El ajuste fiscal de la devolucion no cuadra con el valor historico");
        }
        return new Valuation(
                selectedGross,
                Money.euros(selectedGross.subtract(currentRefund)),
                currentRefund,
                eligibleRefund,
                cumulativeEligibleRefund,
                cumulativeRefund,
                previouslyRefunded,
                remainingValue,
                adjustments);
    }

    private BigDecimal eligibleSelectedGross(
            CommercialDocument original,
            Map<DocumentLine, BigDecimal> selected,
            BigDecimal globalFactor) {
        if (selected.isEmpty()) {
            return Money.euros(BigDecimal.ZERO);
        }
        var historical = loyaltyLines.findAllById(selected.keySet().stream()
                .map(DocumentLine::getId)
                .toList());
        if (!historical.isEmpty()) {
            if (historical.size() != selected.size()
                    || historical.stream().anyMatch(line ->
                            !line.getDocumentId().equals(original.getId()))) {
                throw new IllegalStateException(
                        "La instantanea historica de fidelizacion esta incompleta");
            }
            var eligibleLineIds = historical.stream()
                    .filter(MemberDocumentLoyaltyLine::isEligible)
                    .map(MemberDocumentLoyaltyLine::getDocumentLineId)
                    .collect(Collectors.toSet());
            return Money.euros(selected.entrySet().stream()
                    .filter(entry -> eligibleLineIds.contains(entry.getKey().getId()))
                    .map(entry -> payableUnitAmount(entry.getKey(), globalFactor)
                            .multiply(entry.getValue()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
        }
        var productMap = products.findAllByStoreIdAndIdIn(
                        original.getTiendaId(),
                        selected.keySet().stream().map(DocumentLine::getProductoId).toList())
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        return Money.euros(selected.entrySet().stream()
                .filter(entry -> {
                    var product = productMap.get(entry.getKey().getProductoId());
                    if (product == null) {
                        throw new IllegalStateException(
                                "No se puede reconstruir el catalogo historico del ticket");
                    }
                    return product.getDiscountType() != DiscountType.NONE;
                })
                .map(entry -> payableUnitAmount(entry.getKey(), globalFactor)
                        .multiply(entry.getValue()))
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private BasketValuation remainingBasketValue(
            CommercialDocument original,
            Map<DocumentLine, BigDecimal> remaining,
            BigDecimal globalFactor) {
        if (remaining.isEmpty()) {
            return new BasketValuation(
                    Money.euros(BigDecimal.ZERO), new LinkedHashMap<>());
        }
        var productMap = products.findAllByStoreIdAndIdIn(
                        original.getTiendaId(),
                        remaining.keySet().stream().map(DocumentLine::getProductoId).toList())
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        if (productMap.size() != remaining.keySet().stream()
                .map(DocumentLine::getProductoId).distinct().count()) {
            throw new IllegalStateException(
                    "No se puede reconstruir el catalogo historico del ticket");
        }
        var evaluationLines = remaining.entrySet().stream()
                .map(entry -> evaluationLine(
                        entry.getKey(), entry.getValue(), productMap.get(
                                entry.getKey().getProductoId()), globalFactor))
                .toList();
        var productGross = Money.euros(remaining.entrySet().stream()
                .map(entry -> payableUnitAmount(entry.getKey(), globalFactor)
                        .multiply(entry.getValue()))
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        var taxTotals = selectedTaxTotals(remaining, globalFactor);
        var promotionPreview = historicalPromotionPreview(original, evaluationLines);
        var directDiscount = Money.euros(promotionPreview.discountTotal());
        promotionPreview.appliedPromotions().forEach(benefit -> addTaxAmount(
                taxTotals,
                new TaxKey(benefit.taxIncluded(), benefit.taxRegime(), benefit.taxPercent()),
                benefit.amount().negate()));
        var pendingBeforeCoupon = Money.euros(productGross.subtract(directDiscount))
                .max(Money.euros(BigDecimal.ZERO));
        var couponDiscount = historicalCouponDiscount(original, pendingBeforeCoupon);
        allocateDiscount(taxTotals, selectedTaxTotals(remaining, globalFactor), couponDiscount);
        var manualDiscount = remainingManualDiscount(
                original, productGross, globalFactor);
        allocateDiscount(taxTotals, selectedTaxTotals(remaining, globalFactor), manualDiscount);
        var total = Money.euros(productGross
                .subtract(directDiscount)
                .subtract(couponDiscount)
                .subtract(manualDiscount))
                .max(Money.euros(BigDecimal.ZERO));
        reconcileTaxTotal(taxTotals, total, taxTotals.keySet());
        return new BasketValuation(total, taxTotals);
    }

    private PromotionPreview historicalPromotionPreview(
            CommercialDocument original,
            List<PromotionEvaluationLine> remaining) {
        var versionIds = original.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.PROMOTION)
                .filter(line -> line.getPromotionalCouponId() == null)
                .map(line -> line.getPromotionVersionId() == null
                        ? line.getPromotionId()
                        : line.getPromotionVersionId())
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (versionIds.isEmpty() || remaining.isEmpty()) {
            return new PromotionPreview(List.of(), Money.euros(BigDecimal.ZERO));
        }
        var historical = promotions.findAllById(versionIds);
        if (historical.size() != versionIds.size()) {
            throw new IllegalStateException(
                    "No se puede recuperar la version historica de la promocion");
        }
        var targets = promotionTargets.findByPromocionIdIn(versionIds);
        return promotionEngine.preview(new PromotionEvaluationRequest(
                remaining, historical, targets));
    }

    private Map<TaxKey, BigDecimal> confirmedTaxRefunds(UUID originalDocumentId) {
        var totals = new LinkedHashMap<TaxKey, BigDecimal>();
        for (var row : documents.confirmedReturnTaxTotals(originalDocumentId)) {
            addTaxAmount(totals, new TaxKey(
                    row.getTaxIncluded(), row.getTaxRegime(), row.getTaxPercent()),
                    row.getAmount());
        }
        return totals;
    }

    private static Map<TaxKey, BigDecimal> documentTaxTotals(
            CommercialDocument document,
            BigDecimal globalFactor) {
        var totals = new LinkedHashMap<TaxKey, BigDecimal>();
        document.getLineas().stream()
                .sorted(java.util.Comparator.comparingInt(DocumentLine::getPosicion))
                .forEach(line -> addTaxAmount(
                        totals,
                        TaxKey.from(line),
                        line.getTotal().multiply(globalFactor)));
        reconcileTaxTotal(totals, document.getTotal(), totals.keySet());
        return totals;
    }

    private static Map<TaxKey, BigDecimal> selectedTaxTotals(
            Map<DocumentLine, BigDecimal> selected,
            BigDecimal globalFactor) {
        var totals = new LinkedHashMap<TaxKey, BigDecimal>();
        selected.forEach((line, quantity) -> addTaxAmount(
                totals,
                TaxKey.from(line),
                payableUnitAmount(line, globalFactor).multiply(quantity)));
        return totals;
    }

    private static Map<TaxKey, BigDecimal> subtractTaxTotals(
            Map<TaxKey, BigDecimal> minuend,
            Map<TaxKey, BigDecimal> subtrahend) {
        var result = new LinkedHashMap<TaxKey, BigDecimal>();
        var keys = new LinkedHashSet<TaxKey>();
        keys.addAll(minuend.keySet());
        keys.addAll(subtrahend.keySet());
        keys.forEach(key -> addTaxAmount(result, key,
                minuend.getOrDefault(key, BigDecimal.ZERO)
                        .subtract(subtrahend.getOrDefault(key, BigDecimal.ZERO))));
        return result;
    }

    private static List<TaxAdjustment> returnAdjustments(
            Map<TaxKey, BigDecimal> selectedGross,
            Map<TaxKey, BigDecimal> currentRefunds) {
        var keys = new LinkedHashSet<TaxKey>();
        keys.addAll(selectedGross.keySet());
        keys.addAll(currentRefunds.keySet());
        return keys.stream()
                .map(key -> new TaxAdjustment(
                        key.taxIncluded(),
                        key.taxRegime(),
                        key.taxPercent(),
                        Money.euros(selectedGross.getOrDefault(key, BigDecimal.ZERO)
                                .subtract(currentRefunds.getOrDefault(key, BigDecimal.ZERO)))))
                .filter(adjustment -> adjustment.amount().signum() != 0)
                .toList();
    }

    private static void allocateDiscount(
            Map<TaxKey, BigDecimal> target,
            Map<TaxKey, BigDecimal> weights,
            BigDecimal discount) {
        var remaining = Money.euros(discount);
        if (remaining.signum() <= 0 || weights.isEmpty()) {
            return;
        }
        var positive = weights.entrySet().stream()
                .filter(entry -> entry.getValue().signum() > 0)
                .toList();
        var totalWeight = positive.stream()
                .map(Map.Entry::getValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        for (int index = 0; index < positive.size() && remaining.signum() > 0; index++) {
            var entry = positive.get(index);
            var allocated = index == positive.size() - 1
                    ? remaining
                    : Money.euros(discount.multiply(entry.getValue())
                            .divide(totalWeight, Money.SCALE + 4, Money.ROUNDING))
                            .min(remaining);
            addTaxAmount(target, entry.getKey(), allocated.negate());
            remaining = Money.euros(remaining.subtract(allocated));
        }
        if (remaining.signum() != 0) {
            throw new IllegalStateException(
                    "No se pudo repartir fiscalmente el descuento historico");
        }
    }

    private static void reconcileTaxTotal(
            Map<TaxKey, BigDecimal> totals,
            BigDecimal expected,
            java.util.Collection<TaxKey> preferredKeys) {
        var actual = Money.euros(totals.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        var difference = Money.euros(expected.subtract(actual));
        if (difference.signum() == 0) {
            return;
        }
        var key = preferredKeys.stream().findFirst()
                .or(() -> totals.keySet().stream().findFirst())
                .orElseThrow(() -> new IllegalStateException(
                        "No existe una clave fiscal para cuadrar la devolucion"));
        addTaxAmount(totals, key, difference);
    }

    private static void addTaxAmount(
            Map<TaxKey, BigDecimal> totals,
            TaxKey key,
            BigDecimal amount) {
        totals.merge(key, Money.euros(amount),
                (left, right) -> Money.euros(left.add(right)));
    }

    private BigDecimal historicalCouponDiscount(
            CommercialDocument original,
            BigDecimal pendingAmount) {
        var couponIds = original.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.PROMOTIONAL_COUPON)
                .map(DocumentLine::getPromotionalCouponId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (couponIds.isEmpty() || pendingAmount.signum() <= 0) {
            return Money.euros(BigDecimal.ZERO);
        }
        var historical = coupons.findAllById(couponIds);
        if (historical.size() != couponIds.size()) {
            throw new IllegalStateException(
                    "No se puede recuperar el cupon historico del ticket");
        }
        var remaining = pendingAmount;
        var total = Money.euros(BigDecimal.ZERO);
        for (var coupon : historical) {
            var discount = couponDiscount(coupon, remaining);
            total = Money.euros(total.add(discount));
            remaining = Money.euros(remaining.subtract(discount));
        }
        return total;
    }

    private static BigDecimal couponDiscount(
            PromotionalCoupon coupon,
            BigDecimal pendingAmount) {
        if (coupon.minimumAmount() != null
                && pendingAmount.compareTo(coupon.minimumAmount()) < 0) {
            return Money.euros(BigDecimal.ZERO);
        }
        if (coupon.benefitType() == PromotionalCouponBenefitType.AMOUNT) {
            return Money.euros(coupon.amount().min(pendingAmount));
        }
        var discount = pendingAmount.multiply(coupon.percent())
                .divide(HUNDRED, 2, RoundingMode.HALF_UP);
        if (coupon.maximumDiscount() != null) {
            discount = discount.min(coupon.maximumDiscount());
        }
        return Money.euros(discount.min(pendingAmount));
    }

    private static BigDecimal remainingManualDiscount(
            CommercialDocument original,
            BigDecimal remainingGross,
            BigDecimal globalFactor) {
        var originalDiscount = Money.euros(original.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.MANUAL_DISCOUNT)
                .map(DocumentLine::getTotal)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        if (originalDiscount.signum() == 0 || remainingGross.signum() == 0) {
            return Money.euros(BigDecimal.ZERO);
        }
        var originalGross = Money.euros(original.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.PRODUCT)
                .map(line -> payableUnitAmount(line, globalFactor)
                        .multiply(line.getCantidad().abs()))
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        if (originalGross.signum() == 0) {
            return Money.euros(BigDecimal.ZERO);
        }
        return Money.euros(originalDiscount.multiply(remainingGross)
                .divide(originalGross, Money.SCALE + 4, Money.ROUNDING))
                .min(remainingGross);
    }

    private static PromotionEvaluationLine evaluationLine(
            DocumentLine line,
            BigDecimal quantity,
            Product product,
            BigDecimal globalFactor) {
        return new PromotionEvaluationLine(
                line.getPosicion(),
                line.getProductoId(),
                product.getFamilyId(),
                product.getSubfamilyId(),
                quantity,
                line.getPrecioUnitario(),
                line.isImpuestosIncluidos(),
                line.getRegimenImpuesto(),
                line.getPorcentajeImpuesto(),
                line.getDescuento().signum() > 0
                        || globalFactor.compareTo(BigDecimal.ONE) < 0,
                product.getDiscountType() != DiscountType.NONE,
                payableUnitAmount(line, globalFactor));
    }

    private static BigDecimal payableUnitAmount(
            DocumentLine line,
            BigDecimal globalFactor) {
        return line.getTotal().abs().multiply(globalFactor)
                .divide(line.getCantidad().abs(), Money.SCALE + 4, Money.ROUNDING);
    }

    private static Map<UUID, BigDecimal> canonicalSelection(
            Map<UUID, BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        var result = new LinkedHashMap<UUID, BigDecimal>();
        values.forEach((lineId, quantity) -> {
            var id = Objects.requireNonNull(lineId, "lineId");
            var value = quantity(Objects.requireNonNull(quantity, "quantity"));
            if (value.signum() <= 0) {
                throw new IllegalArgumentException(
                        "La cantidad a devolver debe ser positiva");
            }
            result.merge(id, value, BigDecimal::add);
        });
        return Map.copyOf(result);
    }

    private static BigDecimal quantity(BigDecimal value) {
        if (value == null || value.signum() < 0
                || value.stripTrailingZeros().scale() > 3) {
            throw new IllegalArgumentException(
                    "La cantidad debe ser positiva y tener hasta tres decimales");
        }
        return value.setScale(3, Money.ROUNDING);
    }

    public record Valuation(
            BigDecimal selectedGross,
            BigDecimal lostBenefits,
            BigDecimal refundableAmount,
            BigDecimal eligibleRefundableAmount,
            BigDecimal cumulativeEligibleRefundableAmount,
            BigDecimal cumulativeRefundableAmount,
            BigDecimal previouslyRefundedAmount,
            BigDecimal remainingBasketValue,
            List<TaxAdjustment> taxAdjustments) {
        public Valuation {
            taxAdjustments = List.copyOf(taxAdjustments);
        }
    }

    public record TaxAdjustment(
            boolean taxesIncluded,
            String taxRegime,
            BigDecimal taxPercentage,
            BigDecimal amount) {
    }

    private record BasketValuation(
            BigDecimal total,
            Map<TaxKey, BigDecimal> taxTotals) {
    }

    private record TaxKey(
            boolean taxIncluded,
            String taxRegime,
            BigDecimal taxPercent) {
        private TaxKey {
            taxRegime = Objects.requireNonNull(taxRegime, "taxRegime");
            taxPercent = Money.validPercentage(taxPercent);
        }

        private static TaxKey from(DocumentLine line) {
            return new TaxKey(
                    line.isImpuestosIncluidos(),
                    line.getRegimenImpuesto(),
                    line.getPorcentajeImpuesto());
        }
    }
}
