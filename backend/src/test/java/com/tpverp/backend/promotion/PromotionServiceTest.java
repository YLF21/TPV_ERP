package com.tpverp.backend.promotion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private CurrentOrganization organization;

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
        when(promotions.findActiveLineageForUpdate(company.getId(), original.id())).thenReturn(List.of(original));

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
        when(promotions.findActiveLineageForUpdate(company.getId(), original.id()))
                .thenReturn(List.of(original), List.of(original, duplicateA));

        service().activate(duplicateA.id());
        service().activate(duplicateB.id());

        assertThat(original.status()).isEqualTo(PromotionStatus.INACTIVE);
        assertThat(duplicateA.status()).isEqualTo(PromotionStatus.INACTIVE);
        assertThat(duplicateB.status()).isEqualTo(PromotionStatus.ACTIVE);
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
                new BigDecimal("50.00")));

        assertThat(view.name()).isEqualTo("Segunda unidad");
        assertThat(view.type()).isEqualTo(PromotionType.SECOND_UNIT_PERCENT);
        assertThat(view.discountPercent()).isEqualByComparingTo("50.00");
        assertThat(view.status()).isEqualTo(PromotionStatus.DRAFT);
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

    private PromotionService service() {
        return new PromotionService(promotions, organization);
    }

    private void currentCompany() {
        when(organization.currentCompany()).thenReturn(company);
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
