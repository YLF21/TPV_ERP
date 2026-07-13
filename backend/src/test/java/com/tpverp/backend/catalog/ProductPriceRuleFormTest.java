package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductPriceRuleFormTest {

    private static final UUID REFERENCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void acceptsTheConditionAndActionMatrixForEverySupportedScope() {
        List<ProductPriceRuleForm.Definition> forms = List.of(
                form(
                        ProductPriceRuleForm.Scope.PRICES,
                        List.of(costCondition()),
                        List.of(
                                fixed(ProductPriceRuleForm.PriceField.SALE_PRICE),
                                margin(ProductPriceRuleForm.PriceField.MEMBER_PRICE),
                                decimal(ProductPriceRuleForm.PriceField.WHOLESALE_PRICE),
                                new ProductPriceRuleForm.PriceUseModeAction(PriceUseMode.MEMBER_PRICE))),
                form(
                        ProductPriceRuleForm.Scope.OFFER,
                        List.of(costCondition()),
                        List.of(
                                fixed(ProductPriceRuleForm.PriceField.OFFER_PRICE),
                                decimal(ProductPriceRuleForm.PriceField.OFFER_PRICE),
                                offer(null))),
                form(
                        ProductPriceRuleForm.Scope.STOCK,
                        List.of(stockCondition(), costCondition()),
                        List.of(offer(new BigDecimal("10.00")))),
                form(
                        ProductPriceRuleForm.Scope.SUPPLIER,
                        List.of(reference(ProductPriceRuleForm.ReferenceField.SUPPLIER), costCondition()),
                        List.of(
                                fixed(ProductPriceRuleForm.PriceField.SALE_PRICE),
                                offer(new BigDecimal("15.00")))),
                form(
                        ProductPriceRuleForm.Scope.FAMILY,
                        List.of(reference(ProductPriceRuleForm.ReferenceField.SUBFAMILY)),
                        List.of(fixed(ProductPriceRuleForm.PriceField.MEMBER_PRICE))),
                form(
                        ProductPriceRuleForm.Scope.PRODUCT_TYPE,
                        List.of(new ProductPriceRuleForm.ProductTypeCondition(
                                ProductPriceRuleForm.SetComparator.IN,
                                List.of(ProductType.UNIT))),
                        List.of(fixed(ProductPriceRuleForm.PriceField.WHOLESALE_PRICE))),
                form(
                        ProductPriceRuleForm.Scope.PRODUCT_LIST,
                        List.of(new ProductPriceRuleForm.ProductListCondition(
                                ProductPriceRuleForm.SetComparator.IN,
                                List.of(REFERENCE_ID))),
                        List.of(fixed(ProductPriceRuleForm.PriceField.SALE_PRICE))));

        assertThatCode(() -> ProductPriceRuleForm.validateAndCopy(forms))
                .doesNotThrowAnyException();
    }

    @Test
    void selectorScopesRequireTheirOwnSelectorCondition() {
        assertInvalid(
                form(ProductPriceRuleForm.Scope.STOCK, List.of(costCondition()), List.of(offer(null))),
                "forms[0] requiere una condicion LOCAL_STOCK o TOTAL_STOCK");
        assertInvalid(
                form(ProductPriceRuleForm.Scope.SUPPLIER, List.of(costCondition()),
                        List.of(fixed(ProductPriceRuleForm.PriceField.SALE_PRICE))),
                "forms[0] requiere una condicion de proveedor");
        assertInvalid(
                form(ProductPriceRuleForm.Scope.FAMILY, List.of(costCondition()),
                        List.of(fixed(ProductPriceRuleForm.PriceField.SALE_PRICE))),
                "forms[0] requiere una condicion de familia o subfamilia");
        assertInvalid(
                form(ProductPriceRuleForm.Scope.PRODUCT_TYPE, List.of(costCondition()),
                        List.of(fixed(ProductPriceRuleForm.PriceField.SALE_PRICE))),
                "forms[0] requiere una condicion de tipo de producto");
        assertInvalid(
                form(ProductPriceRuleForm.Scope.PRODUCT_LIST, List.of(costCondition()),
                        List.of(fixed(ProductPriceRuleForm.PriceField.SALE_PRICE))),
                "forms[0] requiere una condicion de lista de productos");
    }

    @Test
    void rejectsConditionsOwnedByAnotherScope() {
        assertInvalid(
                form(ProductPriceRuleForm.Scope.PRICES, List.of(stockCondition()),
                        List.of(fixed(ProductPriceRuleForm.PriceField.SALE_PRICE))),
                "NUMBER.LOCAL_STOCK no se admite para PRICES");
        assertInvalid(
                form(ProductPriceRuleForm.Scope.OFFER,
                        List.of(reference(ProductPriceRuleForm.ReferenceField.SUPPLIER)),
                        List.of(offer(null))),
                "REFERENCE.SUPPLIER no se admite para OFFER");
        assertInvalid(
                form(ProductPriceRuleForm.Scope.SUPPLIER,
                        List.of(reference(ProductPriceRuleForm.ReferenceField.FAMILY)),
                        List.of(fixed(ProductPriceRuleForm.PriceField.SALE_PRICE))),
                "REFERENCE.FAMILY no se admite para SUPPLIER");
        assertInvalid(
                form(ProductPriceRuleForm.Scope.FAMILY,
                        List.of(reference(ProductPriceRuleForm.ReferenceField.SUPPLIER)),
                        List.of(fixed(ProductPriceRuleForm.PriceField.SALE_PRICE))),
                "REFERENCE.SUPPLIER no se admite para FAMILY");
    }

    @Test
    void rejectsActionsOutsideTheScopeContract() {
        assertInvalid(
                form(ProductPriceRuleForm.Scope.PRICES, List.of(),
                        List.of(fixed(ProductPriceRuleForm.PriceField.OFFER_PRICE))),
                "FIXED_PRICE.OFFER_PRICE no se admite para PRICES");
        assertInvalid(
                form(ProductPriceRuleForm.Scope.OFFER, List.of(),
                        List.of(fixed(ProductPriceRuleForm.PriceField.SALE_PRICE))),
                "FIXED_PRICE.SALE_PRICE no se admite para OFFER");
        assertInvalid(
                form(ProductPriceRuleForm.Scope.STOCK, List.of(stockCondition()),
                        List.of(new ProductPriceRuleForm.PriceUseModeAction(PriceUseMode.OFFER_PRICE))),
                "PRICE_USE_MODE no se admite para STOCK");
        assertInvalid(
                form(ProductPriceRuleForm.Scope.SUPPLIER,
                        List.of(reference(ProductPriceRuleForm.ReferenceField.SUPPLIER)),
                        List.of(new ProductPriceRuleForm.PriceUseModeAction(PriceUseMode.NORMAL))),
                "PRICE_USE_MODE no se admite para SUPPLIER");
    }

    @Test
    void percentageDiscountCannotReceiveDecimalAdjustmentOrFixedOfferPrice() {
        assertInvalid(
                form(ProductPriceRuleForm.Scope.OFFER, List.of(), List.of(
                        decimal(ProductPriceRuleForm.PriceField.OFFER_PRICE),
                        offer(new BigDecimal("10.00")))),
                "no puede aplicar ajuste decimal al descuento porcentual de oferta");
        assertInvalid(
                form(ProductPriceRuleForm.Scope.OFFER, List.of(), List.of(
                        fixed(ProductPriceRuleForm.PriceField.OFFER_PRICE),
                        offer(new BigDecimal("10.00")))),
                "no puede combinar precio de oferta y descuento porcentual de oferta");
    }

    @Test
    void rejectsAmbiguousOrInactiveOfferCombinations() {
        assertInvalid(
                form(ProductPriceRuleForm.Scope.PRICES, List.of(), List.of(
                        fixed(ProductPriceRuleForm.PriceField.SALE_PRICE),
                        margin(ProductPriceRuleForm.PriceField.SALE_PRICE))),
                "define mas de un precio base para SALE_PRICE");
        assertInvalid(
                form(ProductPriceRuleForm.Scope.OFFER, List.of(), List.of(
                        new ProductPriceRuleForm.OfferAction(
                                false, new BigDecimal("10.00"), null, null))),
                "no puede aplicar descuento porcentual con la oferta inactiva");
        assertInvalid(
                form(ProductPriceRuleForm.Scope.OFFER, List.of(), List.of(
                        fixed(ProductPriceRuleForm.PriceField.OFFER_PRICE),
                        new ProductPriceRuleForm.OfferAction(false, null, null, null))),
                "no puede modificar el precio de oferta y desactivar la oferta");
    }

    private static ProductPriceRuleForm.Definition form(
            ProductPriceRuleForm.Scope scope,
            List<ProductPriceRuleForm.Condition> conditions,
            List<ProductPriceRuleForm.Action> actions) {
        return new ProductPriceRuleForm.Definition(scope, conditions, actions);
    }

    private static ProductPriceRuleForm.NumericCondition costCondition() {
        return new ProductPriceRuleForm.NumericCondition(
                ProductPriceRuleForm.NumericField.NET_COST,
                ProductPriceRuleForm.NumericComparator.GTE,
                BigDecimal.ZERO,
                null);
    }

    private static ProductPriceRuleForm.NumericCondition stockCondition() {
        return new ProductPriceRuleForm.NumericCondition(
                ProductPriceRuleForm.NumericField.LOCAL_STOCK,
                ProductPriceRuleForm.NumericComparator.GTE,
                BigDecimal.ZERO,
                null);
    }

    private static ProductPriceRuleForm.ReferenceCondition reference(
            ProductPriceRuleForm.ReferenceField field) {
        return new ProductPriceRuleForm.ReferenceCondition(
                field,
                ProductPriceRuleForm.SetComparator.IN,
                List.of(REFERENCE_ID));
    }

    private static ProductPriceRuleForm.FixedPriceAction fixed(
            ProductPriceRuleForm.PriceField field) {
        return new ProductPriceRuleForm.FixedPriceAction(field, new BigDecimal("20.00"));
    }

    private static ProductPriceRuleForm.InverseMarginAction margin(
            ProductPriceRuleForm.PriceField field) {
        return new ProductPriceRuleForm.InverseMarginAction(
                field,
                ProductPriceRuleForm.CostBasis.NET,
                new BigDecimal("20.00"));
    }

    private static ProductPriceRuleForm.DecimalEndingAction decimal(
            ProductPriceRuleForm.PriceField field) {
        return new ProductPriceRuleForm.DecimalEndingAction(field, 99);
    }

    private static ProductPriceRuleForm.OfferAction offer(BigDecimal discountPercent) {
        return new ProductPriceRuleForm.OfferAction(
                true,
                discountPercent,
                LocalDate.of(2026, 7, 11),
                null);
    }

    private static void assertInvalid(
            ProductPriceRuleForm.Definition form,
            String expectedMessage) {
        assertThatThrownBy(() -> ProductPriceRuleForm.validateAndCopy(List.of(form)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedMessage);
    }
}
