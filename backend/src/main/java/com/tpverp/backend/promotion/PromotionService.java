package com.tpverp.backend.promotion;

import com.tpverp.backend.organization.CurrentOrganization;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PromotionService {

    private final PromotionRepository promotions;
    private final CurrentOrganization organization;

    public PromotionService(PromotionRepository promotions, CurrentOrganization organization) {
        this.promotions = promotions;
        this.organization = organization;
    }

    @Transactional(readOnly = true)
    public List<PromotionView> list() {
        return promotions.findByEmpresaIdOrderByNombreAsc(companyId()).stream()
                .map(PromotionView::from)
                .toList();
    }

    @Transactional
    public PromotionView create(PromotionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        var promotion = Promotion.draft(companyId(), request.name(), request.type(), request.startDate());
        applyRequest(promotion, request);
        return PromotionView.from(promotions.save(promotion));
    }

    @Transactional
    public PromotionView duplicate(UUID id) {
        var duplicate = promotion(id).duplicateDraft();
        return PromotionView.from(promotions.save(duplicate));
    }

    @Transactional
    public PromotionView activate(UUID id) {
        var companyId = companyId();
        var promotion = promotions.findByIdAndEmpresaId(id, companyId)
                .orElseThrow(() -> new IllegalArgumentException("message.promotion.not_found"));
        var rootId = promotion.rootVersionId();
        promotions.findByIdAndEmpresaIdForUpdate(rootId, companyId)
                .orElseThrow(() -> new IllegalStateException("message.promotion.inconsistent_lineage"));
        promotions.findActiveLineage(companyId, rootId).stream()
                .filter(active -> !active.id().equals(promotion.id()))
                .forEach(Promotion::deactivate);
        promotion.activate();
        return PromotionView.from(promotion);
    }

    @Transactional
    public PromotionView deactivate(UUID id) {
        var promotion = promotion(id);
        promotion.deactivate();
        return PromotionView.from(promotion);
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
                request.customerSegment());
        if (request.type() == PromotionType.BUY_X_PAY_Y) {
            promotion.configureBuyXPayY(request.buyQuantity(), request.payQuantity());
        }
        if (request.type() == PromotionType.SECOND_UNIT_PERCENT) {
            promotion.configureSecondUnitPercent(request.discountPercent());
        }
    }

    private Promotion promotion(UUID id) {
        return promotions.findByIdAndEmpresaId(id, companyId())
                .orElseThrow(() -> new IllegalArgumentException("message.promotion.not_found"));
    }

    private UUID companyId() {
        return organization.currentCompany().getId();
    }

    public record PromotionRequest(
            @NotBlank
            String name,
            @NotNull
            PromotionType type,
            @NotNull
            LocalDate startDate,
            LocalDate endDate,
            PromotionScope scope,
            PromotionCustomerSegment customerSegment,
            BigDecimal buyQuantity,
            BigDecimal payQuantity,
            BigDecimal discountPercent) {
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
            BigDecimal buyQuantity,
            BigDecimal payQuantity,
            BigDecimal discountPercent,
            UUID versionOrigenId,
            boolean used) {

        static PromotionView from(Promotion promotion) {
            return new PromotionView(
                    promotion.id(),
                    promotion.name(),
                    promotion.type(),
                    promotion.status(),
                    promotion.startDate(),
                    promotion.endDate(),
                    promotion.scope(),
                    promotion.customerSegment(),
                    promotion.buyQuantity(),
                    promotion.payQuantity(),
                    promotion.discountPercent(),
                    promotion.versionOrigenId(),
                    promotion.used());
        }
    }
}
