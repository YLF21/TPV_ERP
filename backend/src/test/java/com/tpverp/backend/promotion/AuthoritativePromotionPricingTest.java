package com.tpverp.backend.promotion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

import com.tpverp.backend.catalog.DiscountType;
import com.tpverp.backend.catalog.PriceUseMode;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.document.DocumentLineCommand;
import com.tpverp.backend.party.Customer;
import com.tpverp.backend.party.CustomerRepository;
import com.tpverp.backend.party.Member;
import com.tpverp.backend.party.MemberCategory;
import com.tpverp.backend.party.MemberRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthoritativePromotionPricingTest {

    private static final UUID COMPANY_ID = UUID.randomUUID();
    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final LocalDate DATE = LocalDate.of(2026, 7, 11);

    @Mock CustomerRepository customers;
    @Mock MemberRepository members;
    @Mock Customer customer;
    @Mock Member member;
    @Mock MemberCategory category;
    @Mock Product product;

    @Test
    void memberPriceRequiresRealActiveMember() {
        when(customers.findByIdAndCompanyId(CUSTOMER_ID, COMPANY_ID)).thenReturn(Optional.of(customer));
        when(members.findByCustomerIdAndCompanyId(CUSTOMER_ID, COMPANY_ID)).thenReturn(Optional.of(member));
        when(member.isActive()).thenReturn(true);
        when(member.getId()).thenReturn(UUID.randomUUID());
        when(product.getDiscountType()).thenReturn(DiscountType.MEMBER_PRICE);
        when(product.getSalePrice()).thenReturn(new BigDecimal("10.00"));
        when(product.getMemberPrice()).thenReturn(new BigDecimal("7.00"));

        var pricing = service();

        assertThat(pricing.basePrice(product, DATE, pricing.customerContext(COMPANY_ID, CUSTOMER_ID)))
                .isEqualByComparingTo("7.00");
        assertThat(pricing.basePrice(
                product, DATE, AuthoritativePromotionPricing.CustomerContext.anonymous()))
                .isEqualByComparingTo("10.00");
    }

    @Test
    void offerDiscountUsesDocumentDate() {
        when(product.getDiscountType()).thenReturn(DiscountType.DISCOUNT_PRICE);
        when(product.getPriceUseMode()).thenReturn(PriceUseMode.OFFER_DISCOUNT);
        when(product.getSalePrice()).thenReturn(new BigDecimal("10.00"));
        when(product.isOfferActive()).thenReturn(true);
        when(product.getOfferFrom()).thenReturn(DATE.minusDays(1));
        when(product.getOfferUntil()).thenReturn(DATE);
        when(product.getOfferDiscountPercent()).thenReturn(new BigDecimal("20.00"));

        assertThat(service().basePrice(
                product, DATE, AuthoritativePromotionPricing.CustomerContext.anonymous()))
                .isEqualByComparingTo("8.00");
        assertThat(service().basePrice(
                product, DATE.plusDays(1), AuthoritativePromotionPricing.CustomerContext.anonymous()))
                .isEqualByComparingTo("10.00");
    }

    @Test
    void noneForcesSalePriceAndRemovesManualLineDiscount() {
        when(product.getDiscountType()).thenReturn(DiscountType.NONE);
        when(product.getSalePrice()).thenReturn(new BigDecimal("10.00"));
        var line = new DocumentLineCommand(
                UUID.randomUUID(), BigDecimal.ONE, "P-1", "Producto", "OFERTA",
                BigDecimal.ONE, new BigDecimal("25.00"), true, "IVA", new BigDecimal("21.00"));

        var priced = service().priceLine(
                product, DATE, AuthoritativePromotionPricing.CustomerContext.anonymous(), line);

        assertThat(priced.precioUnitario()).isEqualByComparingTo("10.00");
        assertThat(priced.descuento()).isZero();
        assertThat(priced.tarifa()).isEqualTo("VENTA");
    }

    @Test
    void activeCategoryDiscountSurvivesFinalPricingForNormalProduct() {
        when(customers.findByIdAndCompanyId(CUSTOMER_ID, COMPANY_ID)).thenReturn(Optional.of(customer));
        when(members.findByCustomerIdAndCompanyId(CUSTOMER_ID, COMPANY_ID)).thenReturn(Optional.of(member));
        when(member.isActive()).thenReturn(true);
        when(member.getId()).thenReturn(UUID.randomUUID());
        when(member.getMemberCategory()).thenReturn(category);
        when(category.getId()).thenReturn(UUID.randomUUID());
        when(category.isActive()).thenReturn(true);
        when(category.isDiscountEnabled()).thenReturn(true);
        when(category.getDiscountPercent()).thenReturn(new BigDecimal("5.00"));
        when(product.getDiscountType()).thenReturn(DiscountType.NONE);
        when(product.getSalePrice()).thenReturn(new BigDecimal("100.00"));
        var line = line(new BigDecimal("3.00"));
        var pricing = service();

        var priced = pricing.priceLine(
                product, DATE, pricing.customerContext(COMPANY_ID, CUSTOMER_ID), line);

        assertThat(priced.precioUnitario()).isEqualByComparingTo("100.00");
        assertThat(priced.descuento()).isEqualByComparingTo("5.00");
    }

    @Test
    void memberPriceUsesDiscountTypeEvenWhenLegacyPriceModeIsNormal() {
        when(customers.findByIdAndCompanyId(CUSTOMER_ID, COMPANY_ID)).thenReturn(Optional.of(customer));
        when(members.findByCustomerIdAndCompanyId(CUSTOMER_ID, COMPANY_ID)).thenReturn(Optional.of(member));
        when(member.isActive()).thenReturn(true);
        when(member.getId()).thenReturn(UUID.randomUUID());
        when(product.getDiscountType()).thenReturn(DiscountType.MEMBER_PRICE);
        when(product.getSalePrice()).thenReturn(new BigDecimal("100.00"));
        when(product.getMemberPrice()).thenReturn(new BigDecimal("80.00"));
        var pricing = service();

        var priced = pricing.priceLine(
                product, DATE, pricing.customerContext(COMPANY_ID, CUSTOMER_ID), line(BigDecimal.ZERO));

        assertThat(priced.precioUnitario()).isEqualByComparingTo("80.00");
        assertThat(priced.tarifa()).isEqualTo("MEMBER");
    }

    @Test
    void missingMemberPriceFallsBackToSalePrice() {
        when(customers.findByIdAndCompanyId(CUSTOMER_ID, COMPANY_ID)).thenReturn(Optional.of(customer));
        when(members.findByCustomerIdAndCompanyId(CUSTOMER_ID, COMPANY_ID)).thenReturn(Optional.of(member));
        when(member.isActive()).thenReturn(true);
        when(member.getId()).thenReturn(UUID.randomUUID());
        when(product.getDiscountType()).thenReturn(DiscountType.MEMBER_PRICE);
        when(product.getSalePrice()).thenReturn(new BigDecimal("100.00"));
        var pricing = service();

        var priced = pricing.priceLine(
                product, DATE, pricing.customerContext(COMPANY_ID, CUSTOMER_ID), line(BigDecimal.ZERO));

        assertThat(priced.precioUnitario()).isEqualByComparingTo("100.00");
        assertThat(priced.tarifa()).isEqualTo("VENTA");
    }

    @Test
    void inactiveCategoryContributesNoTierDiscount() {
        when(customers.findByIdAndCompanyId(CUSTOMER_ID, COMPANY_ID)).thenReturn(Optional.of(customer));
        when(members.findByCustomerIdAndCompanyId(CUSTOMER_ID, COMPANY_ID)).thenReturn(Optional.of(member));
        when(member.isActive()).thenReturn(true);
        when(member.getId()).thenReturn(UUID.randomUUID());
        when(member.getMemberCategory()).thenReturn(category);
        when(category.getId()).thenReturn(UUID.randomUUID());
        when(category.isActive()).thenReturn(false);
        when(product.getDiscountType()).thenReturn(DiscountType.NONE);
        when(product.getSalePrice()).thenReturn(new BigDecimal("100.00"));
        var pricing = service();

        var context = pricing.customerContext(COMPANY_ID, CUSTOMER_ID);
        var priced = pricing.priceLine(product, DATE, context, line(BigDecimal.ZERO));
        pricing.priceLine(product, DATE, context, line(BigDecimal.ZERO));

        assertThat(priced.descuento()).isZero();
        verify(members, times(1)).findByCustomerIdAndCompanyId(CUSTOMER_ID, COMPANY_ID);
    }

    private static DocumentLineCommand line(BigDecimal discount) {
        return new DocumentLineCommand(
                UUID.randomUUID(), BigDecimal.ONE, "P-1", "Producto", "VENTA",
                new BigDecimal("1.00"), discount, true, "IVA", new BigDecimal("21.00"));
    }

    private AuthoritativePromotionPricing service() {
        return new AuthoritativePromotionPricing(customers, members);
    }
}
