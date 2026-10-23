package com.tpverp.backend.promotion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.tpverp.backend.catalog.ProductCommercialHistoryService;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PromotionUpdateServiceTest {
    @Mock private PromotionRepository promotions;
    @Mock private PromotionTargetRepository targets;
    @Mock private CurrentOrganization organization;
    @Mock private PromotionCatalogGateway catalog;
    @Mock private AuthoritativePromotionPricing pricing;
    @Mock private ProductCommercialHistoryService history;
    @Mock private Company company;
    @Mock private Store store;
    private final UUID companyId = UUID.randomUUID();
    private final UUID storeId = UUID.randomUUID();
    private PromotionService service;

    @BeforeEach
    void setup() {
        service = new PromotionService(promotions, targets, new PromotionEngine(), organization, catalog, pricing);
        service.setCommercialHistory(history);
        lenient().when(organization.currentCompany()).thenReturn(company);
        lenient().when(company.getId()).thenReturn(companyId);
        lenient().when(organization.currentStore()).thenReturn(store);
        lenient().when(store.getId()).thenReturn(storeId);
    }

    @Test
    void updatesActiveUnusedPromotionAndReplacesTargetsWithHistoryAroundTheMutation() {
        var source = promotion();
        source.configureManagementFields(source.startDate(), null, PromotionScope.PRODUCT_LIST,
                PromotionCustomerSegment.ALL, null);
        source.activate();
        ReflectionTestUtils.setField(source, "version", 4L);
        lockable(source);
        var oldTarget = new PromotionTarget(source.id(), PromotionTargetType.PRODUCT, UUID.randomUUID());
        var newProductId = UUID.randomUUID();
        when(targets.findByPromocionId(source.id())).thenReturn(List.of(oldTarget));
        var historyNames = new ArrayList<String>();
        doAnswer(invocation -> {
            historyNames.add(((Promotion) invocation.getArgument(0)).name());
            return null;
        }).when(history).recordPromotion(any(), any());
        doAnswer(invocation -> {
            ReflectionTestUtils.setField(source, "version", 5L);
            return null;
        }).when(promotions).flush();

        var result = service.update(source.id(), new PromotionService.PromotionUpdateRequest(
                4L, request("Updated", List.of(new PromotionService.PromotionTargetRequest(
                        PromotionTargetType.PRODUCT, newProductId)))));

        assertThat(result.id()).isEqualTo(source.id());
        assertThat(result.version()).isEqualTo(5);
        assertThat(result.status()).isEqualTo(PromotionStatus.ACTIVE);
        assertThat(result.discountPercent()).isEqualByComparingTo("25");
        assertThat(result.targets()).extracting(PromotionService.PromotionTargetRequest::targetId)
                .containsExactly(newProductId);
        assertThat(historyNames).containsExactly("Original", "Updated");
        var order = inOrder(catalog, promotions, history, targets);
        order.verify(catalog).lockCompanyForCatalogMutation(companyId);
        order.verify(promotions).findRootVersionId(source.id(), companyId);
        order.verify(promotions).findByIdAndEmpresaIdForUpdate(source.id(), companyId);
        order.verify(catalog).validateTargets(storeId, List.of(
                new PromotionCatalogGateway.TargetReference(PromotionTargetType.PRODUCT, oldTarget.targetId())));
        order.verify(catalog).validateTargets(storeId, List.of(
                new PromotionCatalogGateway.TargetReference(PromotionTargetType.PRODUCT, newProductId)));
        order.verify(history).recordPromotion(source, List.of(oldTarget));
        order.verify(targets).deleteAll(List.of(oldTarget));
        order.verify(targets).flush();
        order.verify(targets).saveAll(any());
        order.verify(history).recordPromotion(eq(source), any());
        verify(promotions, never()).save(any());
    }

    @Test
    void usedPromotionProducesNewDraftWithoutChangingSourceOrItsTargets() {
        var source = promotion();
        source.configureManagementFields(source.startDate(), null, PromotionScope.PRODUCT_LIST,
                PromotionCustomerSegment.ALL, null);
        source.activate();
        source.markUsed();
        lockable(source);
        var oldTarget = new PromotionTarget(source.id(), PromotionTargetType.PRODUCT, UUID.randomUUID());
        when(targets.findByPromocionId(source.id())).thenReturn(List.of(oldTarget));
        when(promotions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var productId = UUID.randomUUID();

        var result = service.update(source.id(), new PromotionService.PromotionUpdateRequest(
                0L, request("New conditions", List.of(new PromotionService.PromotionTargetRequest(
                        PromotionTargetType.PRODUCT, productId)))));

        assertThat(result.id()).isNotEqualTo(source.id());
        assertThat(result.versionOrigenId()).isEqualTo(source.id());
        assertThat(result.status()).isEqualTo(PromotionStatus.DRAFT);
        assertThat(result.used()).isFalse();
        assertThat(result.targets()).extracting(PromotionService.PromotionTargetRequest::targetId)
                .containsExactly(productId);
        assertThat(source.name()).isEqualTo("Original");
        assertThat(source.status()).isEqualTo(PromotionStatus.ACTIVE);
        assertThat(source.used()).isTrue();
        assertThat(source.discountPercent()).isEqualByComparingTo("50");
        verify(targets, never()).deleteAll(any());
        verify(promotions, never()).findActiveLineage(any(), any());
        var order = inOrder(history, promotions);
        order.verify(history).recordPromotion(source, List.of(oldTarget));
        order.verify(promotions).save(argThat(value -> value.id().equals(result.id())));
        order.verify(history).recordPromotion(argThat(value -> value.id().equals(result.id())), any());
    }

    @Test
    void locksRootBeforeUpdatingSiblingVersionAndKeepsInactiveState() {
        var root = promotion();
        var source = root.duplicateDraft();
        source.deactivate();
        when(promotions.findRootVersionId(source.id(), companyId)).thenReturn(Optional.of(root.id()));
        when(promotions.findByIdAndEmpresaIdForUpdate(root.id(), companyId)).thenReturn(Optional.of(root));
        when(promotions.findByIdAndEmpresaIdForUpdate(source.id(), companyId)).thenReturn(Optional.of(source));

        var result = service.update(source.id(), new PromotionService.PromotionUpdateRequest(
                0L, request("Changed", List.of())));

        assertThat(result.status()).isEqualTo(PromotionStatus.INACTIVE);
        var order = inOrder(promotions);
        order.verify(promotions).findByIdAndEmpresaIdForUpdate(root.id(), companyId);
        order.verify(promotions).findByIdAndEmpresaIdForUpdate(source.id(), companyId);
    }

    @Test
    void rejectsStaleVersionBeforeChangingAnything() {
        var source = promotion();
        ReflectionTestUtils.setField(source, "version", 7L);
        lockable(source);

        assertThatThrownBy(() -> service.update(source.id(), new PromotionService.PromotionUpdateRequest(
                6L, request("Stale", List.of()))))
                .isInstanceOf(OptimisticLockingFailureException.class)
                .hasMessage("PROMOTION_VERSION_CONFLICT");
        assertThat(source.name()).isEqualTo("Original");
        verifyNoInteractions(targets, history);
        verify(promotions, never()).save(any());
    }

    @Test
    void rejectsPromotionOutsideCurrentCompanyBeforeLockingOrReadingTargets() {
        var id = UUID.randomUUID();
        when(promotions.findRootVersionId(id, companyId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(id, new PromotionService.PromotionUpdateRequest(
                0L, request("Other company", List.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("message.promotion.not_found");
        verify(promotions, never()).findByIdAndEmpresaIdForUpdate(any(), any());
        verifyNoInteractions(targets, history);
    }

    @Test
    void rejectsTargetsOutsideCurrentStoreBeforeMutation() {
        var source = promotion();
        lockable(source);
        var productId = UUID.randomUUID();
        var references = List.of(new PromotionCatalogGateway.TargetReference(PromotionTargetType.PRODUCT, productId));
        doNothing().when(catalog).validateTargets(storeId, List.of());
        doThrow(new IllegalArgumentException("Target de producto no encontrado en la tienda"))
                .when(catalog).validateTargets(storeId, references);

        assertThatThrownBy(() -> service.update(source.id(), new PromotionService.PromotionUpdateRequest(
                0L, request("Invalid targets", List.of(new PromotionService.PromotionTargetRequest(
                        PromotionTargetType.PRODUCT, productId))))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(source.name()).isEqualTo("Original");
        verifyNoInteractions(history);
        verify(targets, never()).deleteAll(any());
    }

    @Test
    void rejectsTypeChange() {
        var source = promotion();
        lockable(source);
        var request = new PromotionService.PromotionRequest("Different", PromotionType.BUY_X_PAY_Y,
                LocalDate.of(2026, 7, 1), null, PromotionScope.SALE, PromotionCustomerSegment.ALL,
                null, new BigDecimal("3"), new BigDecimal("2"), null);

        assertThatThrownBy(() -> service.update(source.id(), new PromotionService.PromotionUpdateRequest(0L, request)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("message.promotion.type_cannot_change");
        verifyNoInteractions(targets, history);
    }

    @Test
    void requiresExpectedVersionEvenWhenCalledOutsideController() {
        assertThatThrownBy(() -> service.update(UUID.randomUUID(), new PromotionService.PromotionUpdateRequest(
                null, request("Missing version", List.of()))))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(promotions, targets, history, catalog);
    }

    private void lockable(Promotion promotion) {
        when(promotions.findRootVersionId(promotion.id(), companyId)).thenReturn(Optional.of(promotion.rootVersionId()));
        when(promotions.findByIdAndEmpresaIdForUpdate(promotion.id(), companyId)).thenReturn(Optional.of(promotion));
    }

    private Promotion promotion() {
        var promotion = Promotion.draft(companyId, "Original", PromotionType.SECOND_UNIT_PERCENT, LocalDate.of(2026, 7, 1));
        promotion.configureSecondUnitPercent(new BigDecimal("50"));
        return promotion;
    }

    private PromotionService.PromotionRequest request(String name, List<PromotionService.PromotionTargetRequest> values) {
        return new PromotionService.PromotionRequest(name, PromotionType.SECOND_UNIT_PERCENT,
                LocalDate.of(2026, 7, 1), null,
                values.isEmpty() ? PromotionScope.SALE : PromotionScope.PRODUCT_LIST,
                PromotionCustomerSegment.ALL, null, null, null, new BigDecimal("25"), values);
    }
}
