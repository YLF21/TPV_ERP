package com.tpverp.backend.promotion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.DiscountType;
import com.tpverp.backend.catalog.StoreTax;
import com.tpverp.backend.organization.Store;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PromotionServiceTest {

    @Mock
    private PromotionRepository promotions;
    @Mock
    private PromotionTargetRepository targets;
    @Mock
    private CurrentOrganization organization;
    @Mock
    private PromotionCatalogGateway catalog;
    @Mock
    private AuthoritativePromotionPricing pricing;
    @Mock
    private Store store;

    private final Company company = new Company("B12345678", "Demo SL", Map.of(
            "linea1", "Calle A",
            "ciudad", "Las Palmas",
            "codigoPostal", "35001",
            "provincia", "Las Palmas",
            "pais", "ES"));

    @Test
    void activeUsedPromotionIsVersionedInsteadOfEdited() {
        currentCompany();
        var original = buyXPayY("3x2 Agua");
        original.activate();
        original.markUsed();
        when(promotions.findByIdAndEmpresaId(original.id(), company.getId())).thenReturn(Optional.of(original));
        when(promotions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var duplicate = service().duplicate(original.id());

        var saved = ArgumentCaptor.forClass(Promotion.class);
        verify(promotions).save(saved.capture());
        assertThat(duplicate.id()).isNotEqualTo(original.id());
        assertThat(saved.getValue().versionOrigenId()).isEqualTo(original.id());
        assertThat(saved.getValue().status()).isEqualTo(PromotionStatus.DRAFT);
        when(promotions.findByIdAndEmpresaId(duplicate.id(), company.getId())).thenReturn(Optional.of(saved.getValue()));
        when(promotions.findByIdAndEmpresaIdForUpdate(original.id(), company.getId())).thenReturn(Optional.of(original));
        when(promotions.findActiveLineage(company.getId(), original.id())).thenReturn(List.of(original));

        service().activate(duplicate.id());

        assertThat(original.status()).isEqualTo(PromotionStatus.INACTIVE);
    }

    @Test
    void activatingSiblingVersionDeactivatesPreviouslyActiveSiblingAndOriginal() {
        currentCompany();
        var original = buyXPayY("3x2 Agua");
        original.activate();
        original.markUsed();
        var duplicateA = original.duplicateDraft();
        var duplicateB = original.duplicateDraft();
        when(promotions.findByIdAndEmpresaId(duplicateA.id(), company.getId())).thenReturn(Optional.of(duplicateA));
        when(promotions.findByIdAndEmpresaId(duplicateB.id(), company.getId())).thenReturn(Optional.of(duplicateB));
        when(promotions.findByIdAndEmpresaIdForUpdate(original.id(), company.getId())).thenReturn(Optional.of(original));
        when(promotions.findActiveLineage(company.getId(), original.id()))
                .thenReturn(List.of(original), List.of(original, duplicateA));

        service().activate(duplicateA.id());
        service().activate(duplicateB.id());

        assertThat(original.status()).isEqualTo(PromotionStatus.INACTIVE);
        assertThat(duplicateA.status()).isEqualTo(PromotionStatus.INACTIVE);
        assertThat(duplicateB.status()).isEqualTo(PromotionStatus.ACTIVE);
    }

    @Test
    void activatingVersionLocksRootBeforeReadingActiveLineage() {
        currentCompany();
        var original = buyXPayY("3x2 Agua");
        original.activate();
        original.markUsed();
        var duplicate = original.duplicateDraft();
        when(promotions.findByIdAndEmpresaId(duplicate.id(), company.getId())).thenReturn(Optional.of(duplicate));
        when(promotions.findByIdAndEmpresaIdForUpdate(original.id(), company.getId())).thenReturn(Optional.of(original));
        when(promotions.findActiveLineage(company.getId(), original.id())).thenReturn(List.of(original));

        service().activate(duplicate.id());

        var inOrder = inOrder(promotions);
        inOrder.verify(promotions).findByIdAndEmpresaId(duplicate.id(), company.getId());
        inOrder.verify(promotions).findByIdAndEmpresaIdForUpdate(original.id(), company.getId());
        inOrder.verify(promotions).findActiveLineage(company.getId(), original.id());
        assertThat(original.status()).isEqualTo(PromotionStatus.INACTIVE);
        assertThat(duplicate.status()).isEqualTo(PromotionStatus.ACTIVE);
    }

    @Test
    void duplicateCreatedFromDuplicateKeepsOriginalRootLineage() {
        currentCompany();
        var original = buyXPayY("3x2 Agua");
        original.activate();
        original.markUsed();
        var duplicateA = original.duplicateDraft();
        var duplicateC = original.duplicateDraft();
        when(promotions.findByIdAndEmpresaId(duplicateC.id(), company.getId())).thenReturn(Optional.of(duplicateC));
        when(promotions.findByIdAndEmpresaIdForUpdate(original.id(), company.getId())).thenReturn(Optional.of(original));
        when(promotions.findActiveLineage(company.getId(), original.id())).thenReturn(List.of(original));
        service().activate(duplicateC.id());

        when(promotions.findByIdAndEmpresaId(duplicateA.id(), company.getId())).thenReturn(Optional.of(duplicateA));
        when(promotions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var duplicateB = service().duplicate(duplicateA.id());

        assertThat(duplicateB.versionOrigenId()).isEqualTo(original.id());
        var saved = ArgumentCaptor.forClass(Promotion.class);
        verify(promotions).save(saved.capture());
        when(promotions.findByIdAndEmpresaId(duplicateB.id(), company.getId())).thenReturn(Optional.of(saved.getValue()));
        when(promotions.findActiveLineage(company.getId(), original.id())).thenReturn(List.of(duplicateC));

        service().activate(duplicateB.id());

        assertThat(original.status()).isEqualTo(PromotionStatus.INACTIVE);
        assertThat(duplicateC.status()).isEqualTo(PromotionStatus.INACTIVE);
        assertThat(saved.getValue().status()).isEqualTo(PromotionStatus.ACTIVE);
    }

    @Test
    void draftPromotionCanBeDeletedWhenUnused() {
        currentCompany();
        var promotion = buyXPayY("3x2 Agua");
        when(promotions.findByIdAndEmpresaId(promotion.id(), company.getId())).thenReturn(Optional.of(promotion));

        service().delete(promotion.id());

        verify(promotions).delete(promotion);
    }

    @Test
    void deletingUsedPromotionIsRejected() {
        currentCompany();
        var promotion = buyXPayY("3x2 Agua");
        promotion.markUsed();
        when(promotions.findByIdAndEmpresaId(promotion.id(), company.getId())).thenReturn(Optional.of(promotion));

        assertThatThrownBy(() -> service().delete(promotion.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("promotion.used_requires_new_version");
    }

    @Test
    void activeUnusedPromotionCannotBeDeleted() {
        currentCompany();
        var promotion = buyXPayY("3x2 Agua");
        promotion.activate();
        when(promotions.findByIdAndEmpresaId(promotion.id(), company.getId())).thenReturn(Optional.of(promotion));

        assertThatThrownBy(() -> service().delete(promotion.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("promotion.delete_requires_draft_or_inactive");
    }

    @Test
    void createsSecondUnitPercentPromotionFromRequest() {
        currentCompany();
        when(promotions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var view = service().create(new PromotionService.PromotionRequest(
                "Segunda unidad",
                PromotionType.SECOND_UNIT_PERCENT,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                PromotionScope.SALE,
                PromotionCustomerSegment.ALL,
                null,
                null,
                null,
                new BigDecimal("50.00")));

        assertThat(view.name()).isEqualTo("Segunda unidad");
        assertThat(view.type()).isEqualTo(PromotionType.SECOND_UNIT_PERCENT);
        assertThat(view.discountPercent()).isEqualByComparingTo("50.00");
        assertThat(view.status()).isEqualTo(PromotionStatus.DRAFT);
    }

    @Test
    void createPersistsTargetsForTargetedScope() {
        currentCompany();
        when(promotions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var productId = UUID.randomUUID();

        var view = service().create(new PromotionService.PromotionRequest(
                "Segunda unidad producto",
                PromotionType.SECOND_UNIT_PERCENT,
                LocalDate.of(2026, 7, 1),
                null,
                PromotionScope.PRODUCT_LIST,
                PromotionCustomerSegment.ALL,
                null,
                null,
                null,
                new BigDecimal("50.00"),
                List.of(new PromotionService.PromotionTargetRequest(
                        PromotionTargetType.PRODUCT, productId))));

        @SuppressWarnings("unchecked")
        var savedTargets = ArgumentCaptor.forClass(List.class);
        verify(targets).saveAll(savedTargets.capture());
        assertThat((List<PromotionTarget>) savedTargets.getValue())
                .singleElement()
                .satisfies(target -> {
                    assertThat(target.promotionId()).isEqualTo(view.id());
                    assertThat(target.type()).isEqualTo(PromotionTargetType.PRODUCT);
                    assertThat(target.targetId()).isEqualTo(productId);
                });
        assertThat(view.targets()).containsExactly(new PromotionService.PromotionTargetRequest(
                PromotionTargetType.PRODUCT, productId));
    }

    @Test
    void createRejectsTargetedScopeWithoutTargets() {
        assertThatThrownBy(() -> service().create(new PromotionService.PromotionRequest(
                "Familia vacia",
                PromotionType.SECOND_UNIT_PERCENT,
                LocalDate.of(2026, 7, 1),
                null,
                PromotionScope.FAMILY,
                PromotionCustomerSegment.ALL,
                null,
                null,
                null,
                new BigDecimal("50.00"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target");
        verify(promotions, never()).save(any());
    }

    @Test
    void createRejectsNullRequestCleanly() {
        assertThatThrownBy(() -> service().create(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request");
    }

    @Test
    void listsCurrentCompanyPromotions() {
        currentCompany();
        var promotion = buyXPayY("3x2 Agua");
        when(promotions.findByEmpresaIdOrderByNombreAsc(company.getId())).thenReturn(List.of(promotion));

        var views = service().list();

        assertThat(views).extracting(PromotionService.PromotionView::name)
                .containsExactly("3x2 Agua");
    }

    @Test
    void previewEvaluatesActiveSalePromotionsForCurrentCompany() {
        currentCompany();
        previewPricing(true);
        var promotion = buyXPayY("3x2 Agua");
        promotion.activate();
        when(promotions.findByEmpresaIdAndEstado(company.getId(), PromotionStatus.ACTIVE))
                .thenReturn(List.of(promotion));
        when(targets.findByPromocionIdIn(List.of(promotion.id()))).thenReturn(List.of());

        var preview = service().preview(new PromotionPreviewRequest(
                LocalDate.of(2026, 7, 9),
                null,
                null,
                null,
                List.of(new PromotionPreviewRequest.Line(
                        1,
                        java.util.UUID.randomUUID(),
                        null,
                        null,
                        new BigDecimal("3"),
                        new BigDecimal("5.00"),
                        true,
                        "IVA",
                        new BigDecimal("7.00"),
                        false,
                        true))));

        assertThat(preview.discountTotal()).isEqualByComparingTo("5.00");
        assertThat(preview.appliedPromotions()).singleElement()
                .satisfies(benefit -> {
                    assertThat(benefit.promotionId()).isEqualTo(promotion.id());
                    assertThat(benefit.name()).isEqualTo("3x2 Agua");
                    assertThat(benefit.taxPercent()).isEqualByComparingTo("7.00");
                });
    }

    @Test
    void previewRequiresMatchingMemberCategoryForCategoryPromotion() {
        currentCompany();
        previewPricing(false);
        var categoryId = UUID.randomUUID();
        var otherCategoryId = UUID.randomUUID();
        var promotion = buyXPayY("3x2 Socio Empleado");
        promotion.configureManagementFields(
                LocalDate.of(2026, 7, 1),
                null,
                PromotionScope.SALE,
                PromotionCustomerSegment.MEMBER_CATEGORY,
                categoryId);
        promotion.activate();
        when(promotions.findByEmpresaIdAndEstado(company.getId(), PromotionStatus.ACTIVE))
                .thenReturn(List.of(promotion));

        var preview = service().preview(new PromotionPreviewRequest(
                LocalDate.of(2026, 7, 9),
                null,
                UUID.randomUUID(),
                otherCategoryId,
                List.of(new PromotionPreviewRequest.Line(
                        1,
                        UUID.randomUUID(),
                        null,
                        null,
                        new BigDecimal("3"),
                        new BigDecimal("5.00"),
                        true,
                        "IVA",
                        new BigDecimal("7.00"),
                        false,
                        true))));

        assertThat(preview.appliedPromotions()).isEmpty();
        verify(targets, never()).findByPromocionIdIn(any());
    }

    private PromotionService service() {
        return new PromotionService(
                promotions, targets, new PromotionEngine(), organization, catalog, pricing);
    }

    private void previewPricing(boolean matchesSegment) {
        var storeId = UUID.randomUUID();
        org.mockito.Mockito.lenient().when(organization.currentStore()).thenReturn(store);
        org.mockito.Mockito.lenient().when(store.getId()).thenReturn(storeId);
        org.mockito.Mockito.lenient().when(catalog.products(any(), any())).thenAnswer(invocation -> {
            java.util.Collection<UUID> ids = invocation.getArgument(1);
            return ids.stream().collect(java.util.stream.Collectors.toMap(id -> id, id -> {
                var product = org.mockito.Mockito.mock(Product.class);
                var tax = org.mockito.Mockito.mock(StoreTax.class);
                var snapshot = org.mockito.Mockito.mock(PromotionCatalogGateway.ProductSnapshot.class);
                org.mockito.Mockito.lenient().when(product.getId()).thenReturn(id);
                org.mockito.Mockito.lenient().when(product.getDiscountType()).thenReturn(DiscountType.NORMAL);
                org.mockito.Mockito.lenient().when(product.isTaxesIncluded()).thenReturn(true);
                org.mockito.Mockito.lenient().when(tax.getPercentage()).thenReturn(new BigDecimal("7.00"));
                org.mockito.Mockito.lenient().when(snapshot.product()).thenReturn(product);
                org.mockito.Mockito.lenient().when(snapshot.tax()).thenReturn(tax);
                return snapshot;
            }));
        });
        var customer = new AuthoritativePromotionPricing.CustomerContext(
                null, matchesSegment ? UUID.randomUUID() : null, null);
        org.mockito.Mockito.lenient().when(pricing.customerContext(any(), any())).thenReturn(customer);
        org.mockito.Mockito.lenient().when(pricing.matchesSegment(any(), any())).thenReturn(matchesSegment);
        org.mockito.Mockito.lenient().when(pricing.basePrice(any(), any(), any()))
                .thenReturn(new BigDecimal("5.00"));
    }

    private void currentCompany() {
        when(organization.currentCompany()).thenReturn(company);
        org.mockito.Mockito.lenient().when(organization.currentStore()).thenReturn(store);
        org.mockito.Mockito.lenient().when(store.getId()).thenReturn(UUID.randomUUID());
    }

    private Promotion buyXPayY(String name) {
        var promotion = Promotion.draft(
                company.getId(),
                name,
                PromotionType.BUY_X_PAY_Y,
                LocalDate.of(2026, 7, 1));
        promotion.configureBuyXPayY(new BigDecimal("3"), new BigDecimal("2"));
        return promotion;
    }
}
