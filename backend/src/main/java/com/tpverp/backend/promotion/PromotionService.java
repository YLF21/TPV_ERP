package com.tpverp.backend.promotion;

import com.tpverp.backend.catalog.DiscountType;
import com.tpverp.backend.organization.CurrentOrganization;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PromotionService {

    private final PromotionRepository promotions;
    private final PromotionTargetRepository targets;
    private final PromotionEngine engine;
    private final CurrentOrganization organization;
    private final PromotionCatalogGateway catalog;
    private final AuthoritativePromotionPricing pricing;

    public PromotionService(
            PromotionRepository promotions,
            PromotionTargetRepository targets,
            PromotionEngine engine,
            CurrentOrganization organization,
            PromotionCatalogGateway catalog,
            AuthoritativePromotionPricing pricing) {
        this.promotions = promotions;
        this.targets = targets;
        this.engine = engine;
        this.organization = organization;
        this.catalog = catalog;
        this.pricing = pricing;
    }

    @Transactional(readOnly = true)
    public List<PromotionView> list() {
        var values = promotions.findByEmpresaIdOrderByNombreAsc(companyId());
        var byPromotion = targetMap(values);
        return values.stream()
                .map(promotion -> PromotionView.from(
                        promotion, byPromotion.getOrDefault(promotion.id(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public PromotionPreview preview(PromotionPreviewRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        var documentDate = request.saleDate() == null ? LocalDate.now() : request.saleDate();
        var customer = pricing.customerContext(companyId(), request.customerId());
        var activePromotions = promotions.findByEmpresaIdAndEstado(companyId(), PromotionStatus.ACTIVE).stream()
                .filter(promotion -> appliesOnDate(promotion, documentDate))
                .filter(promotion -> pricing.matchesSegment(promotion, customer))
                .toList();
        var promotionTargets = activePromotions.isEmpty()
                ? List.<PromotionTarget>of()
                : targets.findByPromocionIdIn(activePromotions.stream().map(Promotion::id).toList());
        var storeId = organization.currentStore().getId();
        var productSnapshots = catalog.products(
                storeId, request.lines().stream().map(PromotionPreviewRequest.Line::productId).toList());
        var lines = request.lines().stream().map(line -> {
            var snapshot = productSnapshots.get(line.productId());
            var product = snapshot.product();
            snapshot.validateTaxSnapshot(line.taxIncluded(), line.taxPercent(), line.taxRegime());
            return new PromotionEvaluationLine(
                    line.position(),
                    product.getId(),
                    product.getFamilyId(),
                    product.getSubfamilyId(),
                    line.quantity(),
                    pricing.basePrice(product, documentDate, customer),
                    product.isTaxesIncluded(), line.taxRegime(), snapshot.tax().getPercentage(),
                    false,
                    product.getDiscountType() != DiscountType.NONE);
        }).toList();
        return engine.preview(new PromotionEvaluationRequest(lines, activePromotions, promotionTargets));
    }

    @Transactional
    public PromotionView create(PromotionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        var scope = request.scope() == null ? PromotionScope.SALE : request.scope();
        var requestedTargets = validatedTargets(scope, request.targets());
        catalog.validateTargets(organization.currentStore().getId(), targetReferences(requestedTargets));
        var promotion = Promotion.draft(companyId(), request.name(), request.type(), request.startDate());
        applyRequest(promotion, request);
        var saved = promotions.save(promotion);
        var persistedTargets = requestedTargets.stream()
                .map(target -> new PromotionTarget(saved.id(), target.type(), target.targetId()))
                .toList();
        if (!persistedTargets.isEmpty()) {
            targets.saveAll(persistedTargets);
        }
        return PromotionView.from(saved, persistedTargets);
    }

    @Transactional
    public PromotionView duplicate(UUID id) {
        var source = promotion(id);
        var duplicate = promotions.save(source.duplicateDraft());
        var duplicatedTargets = targets.findByPromocionId(source.id()).stream()
                .map(target -> new PromotionTarget(duplicate.id(), target.type(), target.targetId()))
                .toList();
        if (!duplicatedTargets.isEmpty()) {
            targets.saveAll(duplicatedTargets);
        }
        return PromotionView.from(duplicate, duplicatedTargets);
    }

    @Transactional
    public PromotionView activate(UUID id) {
        var companyId = companyId();
        var promotion = promotions.findByIdAndEmpresaId(id, companyId)
                .orElseThrow(() -> new IllegalArgumentException("message.promotion.not_found"));
        var promotionTargets = targets.findByPromocionId(promotion.id());
        validatedPersistedTargets(promotion.scope(), promotionTargets);
        catalog.validateTargets(organization.currentStore().getId(), promotionTargets.stream()
                .map(target -> new PromotionCatalogGateway.TargetReference(
                        target.type(), target.targetId()))
                .toList());
        var rootId = promotion.rootVersionId();
        promotions.findByIdAndEmpresaIdForUpdate(rootId, companyId)
                .orElseThrow(() -> new IllegalStateException("message.promotion.inconsistent_lineage"));
        promotions.findActiveLineage(companyId, rootId).stream()
                .filter(active -> !active.id().equals(promotion.id()))
                .forEach(Promotion::deactivate);
        promotion.activate();
        return PromotionView.from(promotion, promotionTargets);
    }

    @Transactional
    public PromotionView deactivate(UUID id) {
        var promotion = promotion(id);
        promotion.deactivate();
        return PromotionView.from(promotion, targets.findByPromocionId(promotion.id()));
    }

    @Transactional
    public void delete(UUID id) {
        var promotion = promotion(id);
        if (promotion.used()) {
            throw new IllegalStateException("message.promotion.used_requires_new_version");
        }
        if (promotion.status() == PromotionStatus.ACTIVE) {
            throw new IllegalStateException("message.promotion.delete_requires_draft_or_inactive");
        }
        promotions.delete(promotion);
    }

    private void applyRequest(Promotion promotion, PromotionRequest request) {
        promotion.configureManagementFields(
                request.startDate(),
                request.endDate(),
                request.scope(),
                request.customerSegment(),
                request.memberCategoryId());
        switch (request.type()) {
            case PURCHASE_THRESHOLD_COUPON -> promotion.configurePurchaseThresholdCoupon(
                    request.minimumAmount(), request.couponAmount(), request.couponPercent(),
                    request.couponMaximumDiscount(), request.couponMinimumAmount(),
                    request.couponValidFromDate(), request.couponValidFromDays(),
                    request.couponValidUntilDate(), request.couponValidDays());
            case PURCHASE_THRESHOLD_DISCOUNT -> promotion.configurePurchaseThresholdDiscount(
                    request.minimumAmount(), request.discountAmount(), request.discountPercent(),
                    request.maximumDiscount());
            case BUY_X_PAY_Y -> promotion.configureBuyXPayY(
                    request.buyQuantity(), request.payQuantity(), request.buyXPayYMode());
            case SECOND_UNIT_PERCENT -> promotion.configureSecondUnitPercent(request.discountPercent());
            case FIXED_PACK_PRICE -> promotion.configureFixedPackPrice(
                    request.buyQuantity(), request.packPrice());
            case QUANTITY_DISCOUNT -> promotion.configureQuantityDiscount(
                    request.minimumQuantity(), request.discountAmount(), request.discountPercent(),
                    request.maximumDiscount());
        }
    }

    private List<PromotionTargetRequest> validatedTargets(
            PromotionScope scope,
            List<PromotionTargetRequest> requested) {
        var values = List.copyOf(requested == null ? List.of() : requested);
        if (scope == PromotionScope.SALE) {
            if (!values.isEmpty()) {
                throw new IllegalArgumentException("SALE no admite objetivos");
            }
            return values;
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("el ambito objetivo necesita al menos un target");
        }
        var expected = targetType(scope);
        if (values.stream().anyMatch(value -> value == null || value.type() != expected)) {
            throw new IllegalArgumentException("el tipo de target no coincide con el ambito");
        }
        if (values.stream().map(PromotionTargetRequest::targetId).distinct().count() != values.size()) {
            throw new IllegalArgumentException("los targets no se pueden repetir");
        }
        return values;
    }

    private void validatedPersistedTargets(PromotionScope scope, List<PromotionTarget> persisted) {
        validatedTargets(scope, persisted.stream()
                .map(target -> new PromotionTargetRequest(target.type(), target.targetId()))
                .toList());
    }

    private static List<PromotionCatalogGateway.TargetReference> targetReferences(
            List<PromotionTargetRequest> values) {
        return values.stream()
                .map(target -> new PromotionCatalogGateway.TargetReference(
                        target.type(), target.targetId()))
                .toList();
    }

    private static PromotionTargetType targetType(PromotionScope scope) {
        return switch (scope) {
            case PRODUCT_LIST -> PromotionTargetType.PRODUCT;
            case FAMILY -> PromotionTargetType.FAMILY;
            case SUBFAMILY -> PromotionTargetType.SUBFAMILY;
            case SALE -> throw new IllegalArgumentException("SALE no tiene tipo de target");
        };
    }

    private Map<UUID, List<PromotionTarget>> targetMap(List<Promotion> values) {
        if (values.isEmpty()) {
            return Map.of();
        }
        return targets.findByPromocionIdIn(values.stream().map(Promotion::id).toList()).stream()
                .collect(Collectors.groupingBy(PromotionTarget::promotionId));
    }

    private Promotion promotion(UUID id) {
        return promotions.findByIdAndEmpresaId(id, companyId())
                .orElseThrow(() -> new IllegalArgumentException("message.promotion.not_found"));
    }

    private static boolean appliesOnDate(Promotion promotion, LocalDate date) {
        return !date.isBefore(promotion.startDate())
                && (promotion.endDate() == null || !date.isAfter(promotion.endDate()));
    }

    private UUID companyId() {
        return organization.currentCompany().getId();
    }

    public record PromotionTargetRequest(
            @NotNull PromotionTargetType type,
            @NotNull UUID targetId) {
    }

    public record PromotionRequest(
            @NotBlank String name,
            @NotNull PromotionType type,
            @NotNull LocalDate startDate,
            LocalDate endDate,
            PromotionScope scope,
            PromotionCustomerSegment customerSegment,
            UUID memberCategoryId,
            BigDecimal minimumAmount,
            BigDecimal minimumQuantity,
            BigDecimal buyQuantity,
            BigDecimal payQuantity,
            BuyXPayYMode buyXPayYMode,
            BigDecimal discountAmount,
            BigDecimal discountPercent,
            BigDecimal maximumDiscount,
            BigDecimal packPrice,
            BigDecimal couponAmount,
            BigDecimal couponPercent,
            BigDecimal couponMaximumDiscount,
            BigDecimal couponMinimumAmount,
            LocalDate couponValidFromDate,
            Integer couponValidFromDays,
            LocalDate couponValidUntilDate,
            Integer couponValidDays,
            List<@Valid PromotionTargetRequest> targets) {

        public PromotionRequest(
                String name,
                PromotionType type,
                LocalDate startDate,
                LocalDate endDate,
                PromotionScope scope,
                PromotionCustomerSegment customerSegment,
                UUID memberCategoryId,
                BigDecimal buyQuantity,
                BigDecimal payQuantity,
                BigDecimal discountPercent) {
            this(name, type, startDate, endDate, scope, customerSegment, memberCategoryId,
                    null, null, buyQuantity, payQuantity, null, null, discountPercent,
                    null, null, null, null, null, null, null, null, null, null, List.of());
        }

        public PromotionRequest(
                String name,
                PromotionType type,
                LocalDate startDate,
                LocalDate endDate,
                PromotionScope scope,
                PromotionCustomerSegment customerSegment,
                UUID memberCategoryId,
                BigDecimal buyQuantity,
                BigDecimal payQuantity,
                BigDecimal discountPercent,
                List<PromotionTargetRequest> targets) {
            this(name, type, startDate, endDate, scope, customerSegment, memberCategoryId,
                    null, null, buyQuantity, payQuantity, null, null, discountPercent,
                    null, null, null, null, null, null, null, null, null, null, targets);
        }
    }

    public record PromotionView(
            UUID id,
            String name,
            PromotionType type,
            PromotionStatus status,
            LocalDate startDate,
            LocalDate endDate,
            PromotionScope scope,
            PromotionCustomerSegment customerSegment,
            UUID memberCategoryId,
            BigDecimal minimumAmount,
            BigDecimal minimumQuantity,
            BigDecimal buyQuantity,
            BigDecimal payQuantity,
            BuyXPayYMode buyXPayYMode,
            BigDecimal discountAmount,
            BigDecimal discountPercent,
            BigDecimal maximumDiscount,
            BigDecimal packPrice,
            UUID versionOrigenId,
            boolean used,
            List<PromotionTargetRequest> targets) {

        static PromotionView from(Promotion promotion, List<PromotionTarget> targets) {
            return new PromotionView(
                    promotion.id(), promotion.name(), promotion.type(), promotion.status(),
                    promotion.startDate(), promotion.endDate(), promotion.scope(),
                    promotion.customerSegment(), promotion.memberCategoryId(),
                    promotion.minimumAmount(), promotion.minimumQuantity(), promotion.buyQuantity(),
                    promotion.payQuantity(), promotion.buyXPayYMode(), promotion.discountAmount(),
                    promotion.discountPercent(), promotion.maximumDiscount(), promotion.packPrice(),
                    promotion.versionOrigenId(), promotion.used(), targets.stream()
                    .map(target -> new PromotionTargetRequest(target.type(), target.targetId()))
                    .toList());
        }
    }
}
