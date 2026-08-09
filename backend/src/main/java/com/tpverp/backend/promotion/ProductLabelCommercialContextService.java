package com.tpverp.backend.promotion;

import com.tpverp.backend.document.Money;
import com.tpverp.backend.organization.CurrentOrganization;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductLabelCommercialContextService {

    private static final int MAX_PRODUCTS = 200;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final PromotionRepository promotions;
    private final PromotionTargetRepository targets;
    private final PromotionCatalogGateway catalog;
    private final AuthoritativePromotionPricing pricing;
    private final CurrentOrganization organization;
    private final Clock clock;

    public ProductLabelCommercialContextService(
            PromotionRepository promotions,
            PromotionTargetRepository targets,
            PromotionCatalogGateway catalog,
            AuthoritativePromotionPricing pricing,
            CurrentOrganization organization,
            Clock clock) {
        this.promotions = promotions;
        this.targets = targets;
        this.catalog = catalog;
        this.pricing = pricing;
        this.organization = organization;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ProductCommercialContextView> resolve(List<UUID> requestedProductIds) {
        var productIds = validatedProductIds(requestedProductIds);
        var store = organization.currentStore();
        var companyId = organization.currentCompany().getId();
        var businessDate = LocalDate.now(clock.withZone(ZoneId.of(store.getTimezone())));
        var snapshots = catalog.products(store.getId(), productIds);

        var activePromotions = promotions.findByEmpresaIdAndEstado(
                        companyId, PromotionStatus.ACTIVE).stream()
                .filter(promotion -> promotion.customerSegment() == PromotionCustomerSegment.ALL)
                .filter(promotion -> promotion.scope() != PromotionScope.SALE)
                .filter(promotion -> promotion.type() != PromotionType.PURCHASE_THRESHOLD_COUPON)
                .filter(promotion -> PromotionEligibility.appliesOnDate(promotion, businessDate))
                .sorted(Comparator
                        .comparing(Promotion::endDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Promotion::name)
                        .thenComparing(Promotion::id))
                .toList();
        var promotionTargets = activePromotions.isEmpty()
                ? List.<PromotionTarget>of()
                : targets.findByPromocionIdIn(activePromotions.stream().map(Promotion::id).toList());

        var result = new ArrayList<ProductCommercialContextView>();
        for (var productId : productIds) {
            var product = snapshots.get(productId).product();
            var offer = activeOffer(product, businessDate);
            var productPromotions = activePromotions.stream()
                    .filter(promotion -> PromotionEligibility.matchesProduct(
                            promotion,
                            product.getId(),
                            product.getFamilyId(),
                            product.getSubfamilyId(),
                            promotionTargets))
                    .map(PromotionLabelView::from)
                    .toList();
            result.add(new ProductCommercialContextView(productId, offer, productPromotions));
        }
        return List.copyOf(result);
    }

    private OfferLabelView activeOffer(
            com.tpverp.backend.catalog.Product product,
            LocalDate businessDate) {
        var regularPrice = Money.euros(product.getSalePrice());
        var offerPrice = pricing.basePrice(
                product, businessDate, AuthoritativePromotionPricing.CustomerContext.anonymous());
        if (regularPrice.signum() <= 0 || offerPrice.compareTo(regularPrice) >= 0) {
            return null;
        }
        var discountPercent = regularPrice.subtract(offerPrice)
                .multiply(HUNDRED)
                .divide(regularPrice, 2, RoundingMode.HALF_UP);
        return new OfferLabelView(
                regularPrice, offerPrice, discountPercent, product.getOfferUntil());
    }

    private static List<UUID> validatedProductIds(List<UUID> requestedProductIds) {
        if (requestedProductIds == null || requestedProductIds.isEmpty()
                || requestedProductIds.size() > MAX_PRODUCTS
                || requestedProductIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("PRODUCT_LABEL_PRODUCTS_INVALID");
        }
        var unique = new LinkedHashSet<>(requestedProductIds);
        if (unique.size() != requestedProductIds.size()) {
            throw new IllegalArgumentException("PRODUCT_LABEL_PRODUCTS_INVALID");
        }
        return List.copyOf(unique);
    }

    public record ProductCommercialContextView(
            UUID productId,
            OfferLabelView offer,
            List<PromotionLabelView> promotions) {
    }

    public record OfferLabelView(
            BigDecimal regularPrice,
            BigDecimal offerPrice,
            BigDecimal discountPercent,
            LocalDate endDate) {
    }

    public record PromotionLabelView(
            UUID id,
            String name,
            PromotionType type,
            LocalDate endDate,
            BigDecimal minimumAmount,
            BigDecimal minimumQuantity,
            BigDecimal buyQuantity,
            BigDecimal payQuantity,
            BuyXPayYMode buyXPayYMode,
            BigDecimal discountAmount,
            BigDecimal discountPercent,
            BigDecimal maximumDiscount,
            BigDecimal packPrice) {

        static PromotionLabelView from(Promotion promotion) {
            return new PromotionLabelView(
                    promotion.id(),
                    promotion.name(),
                    promotion.type(),
                    promotion.endDate(),
                    promotion.minimumAmount(),
                    promotion.minimumQuantity(),
                    promotion.buyQuantity(),
                    promotion.payQuantity(),
                    promotion.buyXPayYMode(),
                    promotion.discountAmount(),
                    promotion.discountPercent(),
                    promotion.maximumDiscount(),
                    promotion.packPrice());
        }
    }
}
