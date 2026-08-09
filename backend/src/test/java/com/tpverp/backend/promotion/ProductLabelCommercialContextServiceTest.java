package com.tpverp.backend.promotion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.catalog.DiscountType;
import com.tpverp.backend.catalog.PriceTier;
import com.tpverp.backend.catalog.PriceUseMode;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductType;
import com.tpverp.backend.catalog.StoreTax;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductLabelCommercialContextServiceTest {

    @Mock private PromotionRepository promotions;
    @Mock private PromotionTargetRepository targets;
    @Mock private PromotionCatalogGateway catalog;
    @Mock private AuthoritativePromotionPricing pricing;
    @Mock private CurrentOrganization organization;

    private final Clock clock = Clock.fixed(
            Instant.parse("2026-08-08T12:00:00Z"), ZoneOffset.UTC);
    private final Map<String, String> address = Map.of(
            "linea1", "Calle A",
            "ciudad", "Las Palmas",
            "codigoPostal", "35001",
            "provincia", "Las Palmas",
            "pais", "ES");
    private final Company company = new Company(
            "B12345678", "Demo SL", address);
    private final Store store = new Store(
            company, "Tienda", address, "hash",
            "Atlantic/Canary", "EUR", "es-ES");

    @Test
    void resolvesAuthoritativeOfferAndOnlyUniversalTargetedPromotions() {
        var familyId = UUID.randomUUID();
        var tax = new StoreTax(store.getId(), new BigDecimal("7"), true);
        var product = new Product(
                store.getId(), familyId, null, tax.getId(),
                ProductType.UNIT, DiscountType.NORMAL,
                "Agua", null, null, BigDecimal.ONE, true);
        product.setPrice(PriceTier.VENTA, new BigDecimal("10.00"));
        product.setPrice(PriceTier.OFERTA, new BigDecimal("8.00"));
        product.configureOffer(true, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));
        product.configurePriceUse(PriceUseMode.OFFER_PRICE, null);

        var publicPromotion = Promotion.draft(
                company.getId(), "3x2 Agua", PromotionType.BUY_X_PAY_Y,
                LocalDate.of(2026, 8, 1));
        publicPromotion.configureManagementFields(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 20),
                PromotionScope.FAMILY, PromotionCustomerSegment.ALL, null);
        publicPromotion.configureBuyXPayY(new BigDecimal("3"), new BigDecimal("2"));
        publicPromotion.activate();

        var memberPromotion = Promotion.draft(
                company.getId(), "Solo socios", PromotionType.SECOND_UNIT_PERCENT,
                LocalDate.of(2026, 8, 1));
        memberPromotion.configureManagementFields(
                LocalDate.of(2026, 8, 1), null,
                PromotionScope.FAMILY, PromotionCustomerSegment.MEMBERS_ONLY, null);
        memberPromotion.configureSecondUnitPercent(new BigDecimal("50"));
        memberPromotion.activate();

        var target = new PromotionTarget(
                publicPromotion.id(), PromotionTargetType.FAMILY, familyId);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(company);
        when(catalog.products(store.getId(), List.of(product.getId())))
                .thenReturn(Map.of(product.getId(),
                        new PromotionCatalogGateway.ProductSnapshot(product, tax)));
        when(promotions.findByEmpresaIdAndEstado(company.getId(), PromotionStatus.ACTIVE))
                .thenReturn(List.of(publicPromotion, memberPromotion));
        when(targets.findByPromocionIdIn(List.of(publicPromotion.id())))
                .thenReturn(List.of(target));
        when(pricing.basePrice(
                product,
                LocalDate.of(2026, 8, 8),
                AuthoritativePromotionPricing.CustomerContext.anonymous()))
                .thenReturn(new BigDecimal("8.00"));

        var result = service().resolve(List.of(product.getId()));

        assertThat(result).singleElement().satisfies(context -> {
            assertThat(context.productId()).isEqualTo(product.getId());
            assertThat(context.offer().regularPrice()).isEqualByComparingTo("10.00");
            assertThat(context.offer().offerPrice()).isEqualByComparingTo("8.00");
            assertThat(context.offer().discountPercent()).isEqualByComparingTo("20.00");
            assertThat(context.promotions()).singleElement().satisfies(promotion -> {
                assertThat(promotion.name()).isEqualTo("3x2 Agua");
                assertThat(promotion.type()).isEqualTo(PromotionType.BUY_X_PAY_Y);
                assertThat(promotion.buyQuantity()).isEqualByComparingTo("3");
                assertThat(promotion.payQuantity()).isEqualByComparingTo("2");
            });
        });
    }

    @Test
    void rejectsDuplicateProductIdsBeforeReadingTenantData() {
        var productId = UUID.randomUUID();

        assertThatThrownBy(() -> service().resolve(List.of(productId, productId)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("PRODUCT_LABEL_PRODUCTS_INVALID");

        verify(organization, never()).currentStore();
    }

    private ProductLabelCommercialContextService service() {
        return new ProductLabelCommercialContextService(
                promotions, targets, catalog, pricing, organization, clock);
    }
}
