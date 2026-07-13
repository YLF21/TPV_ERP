package com.tpverp.backend.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class ProductPriceRuleForm {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal MAX_MARGIN_PERCENT = new BigDecimal("99.99");
    private static final int MAX_FORMS = 100;
    private static final int MAX_ITEMS_PER_FORM = 100;
    private static final int MAX_PRODUCT_IDS = 5_000;
    private static final int MAX_TOTAL_REFERENCES = 20_000;

    private ProductPriceRuleForm() {
    }

    public enum Scope {
        PRICES,
        OFFER,
        STOCK,
        SUPPLIER,
        FAMILY,
        PRODUCT_TYPE,
        PRODUCT_LIST
    }

    public enum NumericField {
        GROSS_COST,
        NET_COST,
        LOCAL_STOCK,
        TOTAL_STOCK
    }

    public enum NumericComparator {
        EQ,
        NE,
        GT,
        GTE,
        LT,
        LTE
    }

    public enum ReferenceField {
        SUPPLIER,
        FAMILY,
        SUBFAMILY
    }

    public enum SetComparator {
        IN,
        NOT_IN
    }

    public enum PriceField {
        SALE_PRICE,
        MEMBER_PRICE,
        WHOLESALE_PRICE,
        OFFER_PRICE
    }

    public enum CostBasis {
        GROSS,
        NET
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = NumericCondition.class, name = "NUMBER"),
        @JsonSubTypes.Type(value = ReferenceCondition.class, name = "REFERENCE"),
        @JsonSubTypes.Type(value = ProductTypeCondition.class, name = "PRODUCT_TYPE"),
        @JsonSubTypes.Type(value = ProductListCondition.class, name = "PRODUCT_LIST")
    })
    public sealed interface Condition permits NumericCondition, ReferenceCondition,
            ProductTypeCondition, ProductListCondition {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record NumericCondition(
            @NotNull NumericField field,
            @NotNull NumericComparator comparator,
            @NotNull BigDecimal value,
            UUID warehouseId) implements Condition {

        public NumericCondition {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(comparator, "comparator");
            Objects.requireNonNull(value, "value");
            if (field != NumericField.LOCAL_STOCK && warehouseId != null) {
                throw new IllegalArgumentException(
                        "warehouseId solo se admite para LOCAL_STOCK");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ReferenceCondition(
            @NotNull ReferenceField field,
            @NotNull SetComparator comparator,
            @NotEmpty List<UUID> values) implements Condition {

        public ReferenceCondition {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(comparator, "comparator");
            values = immutableValues(values, "values", MAX_PRODUCT_IDS);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ProductTypeCondition(
            @NotNull SetComparator comparator,
            @NotEmpty List<ProductType> values) implements Condition {

        public ProductTypeCondition {
            Objects.requireNonNull(comparator, "comparator");
            values = immutableValues(values, "values", ProductType.values().length);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ProductListCondition(
            @NotNull SetComparator comparator,
            @NotEmpty List<UUID> productIds) implements Condition {

        public ProductListCondition {
            Objects.requireNonNull(comparator, "comparator");
            productIds = immutableValues(productIds, "productIds", MAX_PRODUCT_IDS);
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = FixedPriceAction.class, name = "FIXED_PRICE"),
        @JsonSubTypes.Type(value = InverseMarginAction.class, name = "INVERSE_MARGIN"),
        @JsonSubTypes.Type(value = DecimalEndingAction.class, name = "DECIMAL_ENDING"),
        @JsonSubTypes.Type(value = PriceUseModeAction.class, name = "PRICE_USE_MODE"),
        @JsonSubTypes.Type(value = OfferAction.class, name = "OFFER")
    })
    public sealed interface Action permits FixedPriceAction, InverseMarginAction,
            DecimalEndingAction, PriceUseModeAction, OfferAction {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record FixedPriceAction(
            @NotNull PriceField field,
            @NotNull BigDecimal value) implements Action {

        public FixedPriceAction {
            Objects.requireNonNull(field, "field");
            nonNegative(value, "value");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record InverseMarginAction(
            @NotNull PriceField field,
            @NotNull CostBasis costBasis,
            @NotNull BigDecimal marginPercent) implements Action {

        public InverseMarginAction {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(costBasis, "costBasis");
            Objects.requireNonNull(marginPercent, "marginPercent");
            if (marginPercent.signum() < 0
                    || marginPercent.compareTo(MAX_MARGIN_PERCENT) > 0
                    || marginPercent.stripTrailingZeros().scale() > 2) {
                throw new IllegalArgumentException(
                        "marginPercent debe estar entre 0 y 99.99 con un maximo de 2 decimales");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DecimalEndingAction(
            @NotNull PriceField field,
            int cents) implements Action {

        public DecimalEndingAction {
            Objects.requireNonNull(field, "field");
            if (cents < 0 || cents > 99) {
                throw new IllegalArgumentException("cents debe estar entre 0 y 99");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record PriceUseModeAction(
            @NotNull PriceUseMode value) implements Action {

        public PriceUseModeAction {
            Objects.requireNonNull(value, "value");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record OfferAction(
            boolean active,
            BigDecimal discountPercent,
            LocalDate from,
            LocalDate until) implements Action {

        public OfferAction {
            if (discountPercent != null && (discountPercent.signum() < 0
                    || discountPercent.compareTo(ONE_HUNDRED) > 0)) {
                throw new IllegalArgumentException(
                        "discountPercent debe estar entre 0 y 100");
            }
            if (until != null && (from == null || until.isBefore(from))) {
                throw new IllegalArgumentException("until no puede ser anterior a from");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Definition(
            @NotNull Scope scope,
            @NotNull List<@Valid Condition> conditions,
            @NotEmpty List<@Valid Action> actions) {

        public Definition {
            Objects.requireNonNull(scope, "scope");
            conditions = immutableValues(conditions, "conditions", MAX_ITEMS_PER_FORM, true);
            actions = immutableValues(actions, "actions", MAX_ITEMS_PER_FORM);
        }
    }

    public static List<Definition> validateAndCopy(List<Definition> forms) {
        List<Definition> values = immutableValues(forms, "forms", MAX_FORMS);
        int totalReferences = 0;
        for (int index = 0; index < values.size(); index++) {
            Definition form = values.get(index);
            if (form.actions().isEmpty()) {
                throw new IllegalArgumentException(
                        "forms[" + index + "].actions es obligatorio");
            }
            validateDefinition(index, form);
            for (Condition condition : form.conditions()) {
                totalReferences += switch (condition) {
                    case ReferenceCondition reference -> reference.values().size();
                    case ProductTypeCondition productType -> productType.values().size();
                    case ProductListCondition productList -> productList.productIds().size();
                    case NumericCondition ignored -> 0;
                };
                if (totalReferences > MAX_TOTAL_REFERENCES) {
                    throw new IllegalArgumentException(
                            "forms no puede superar " + MAX_TOTAL_REFERENCES
                                    + " referencias en su JSON");
                }
            }
        }
        return values;
    }

    private static void validateDefinition(int formIndex, Definition form) {
        String path = "forms[" + formIndex + "]";
        boolean hasRequiredSelector = !requiresSelector(form.scope());
        for (int conditionIndex = 0; conditionIndex < form.conditions().size(); conditionIndex++) {
            Condition condition = form.conditions().get(conditionIndex);
            if (!conditionAllowed(form.scope(), condition)) {
                throw new IllegalArgumentException(
                        path + ".conditions[" + conditionIndex + "] "
                                + conditionName(condition) + " no se admite para " + form.scope());
            }
            hasRequiredSelector |= selectsScope(form.scope(), condition);
        }
        if (!hasRequiredSelector) {
            throw new IllegalArgumentException(
                    path + " requiere " + requiredSelectorName(form.scope()));
        }
        validateActions(path, form);
    }

    private static void validateActions(String path, Definition form) {
        EnumSet<PriceField> basePriceFields = EnumSet.noneOf(PriceField.class);
        EnumSet<PriceField> decimalFields = EnumSet.noneOf(PriceField.class);
        OfferAction offer = null;
        int offerActionIndex = -1;
        boolean hasPriceUseMode = false;

        for (int actionIndex = 0; actionIndex < form.actions().size(); actionIndex++) {
            Action action = form.actions().get(actionIndex);
            String actionPath = path + ".actions[" + actionIndex + "]";
            if (!actionAllowed(form.scope(), action)) {
                throw new IllegalArgumentException(
                        actionPath + " " + actionName(action)
                                + " no se admite para " + form.scope());
            }
            switch (action) {
                case FixedPriceAction fixed -> requireSingleBasePrice(
                        basePriceFields, fixed.field(), actionPath);
                case InverseMarginAction margin -> requireSingleBasePrice(
                        basePriceFields, margin.field(), actionPath);
                case DecimalEndingAction decimal -> {
                    if (!decimalFields.add(decimal.field())) {
                        throw new IllegalArgumentException(
                                actionPath + " duplica el ajuste decimal de " + decimal.field());
                    }
                }
                case PriceUseModeAction ignored -> {
                    if (hasPriceUseMode) {
                        throw new IllegalArgumentException(
                                actionPath + " duplica la seleccion de Usar precio");
                    }
                    hasPriceUseMode = true;
                }
                case OfferAction value -> {
                    if (offer != null) {
                        throw new IllegalArgumentException(
                                actionPath + " duplica la configuracion de oferta");
                    }
                    offer = value;
                    offerActionIndex = actionIndex;
                }
            }
        }

        if (offer == null) {
            return;
        }
        String offerPath = path + ".actions[" + offerActionIndex + "]";
        boolean changesOfferPrice = basePriceFields.contains(PriceField.OFFER_PRICE);
        boolean adjustsOfferPrice = decimalFields.contains(PriceField.OFFER_PRICE);
        if (!offer.active() && offer.discountPercent() != null) {
            throw new IllegalArgumentException(
                    offerPath + " no puede aplicar descuento porcentual con la oferta inactiva");
        }
        if (!offer.active() && (changesOfferPrice || adjustsOfferPrice)) {
            throw new IllegalArgumentException(
                    path + " no puede modificar el precio de oferta y desactivar la oferta");
        }
        if (offer.discountPercent() != null && adjustsOfferPrice) {
            throw new IllegalArgumentException(
                    path + " no puede aplicar ajuste decimal al descuento porcentual de oferta");
        }
        if (offer.discountPercent() != null && changesOfferPrice) {
            throw new IllegalArgumentException(
                    path + " no puede combinar precio de oferta y descuento porcentual de oferta");
        }
    }

    private static void requireSingleBasePrice(
            EnumSet<PriceField> fields,
            PriceField field,
            String actionPath) {
        if (!fields.add(field)) {
            throw new IllegalArgumentException(
                    actionPath + " define mas de un precio base para " + field);
        }
    }

    private static boolean conditionAllowed(Scope scope, Condition condition) {
        if (isCostCondition(condition)) {
            return true;
        }
        return switch (scope) {
            case PRICES, OFFER -> false;
            case STOCK -> isStockCondition(condition);
            case SUPPLIER -> isReferenceCondition(condition, ReferenceField.SUPPLIER);
            case FAMILY -> isReferenceCondition(condition, ReferenceField.FAMILY)
                    || isReferenceCondition(condition, ReferenceField.SUBFAMILY);
            case PRODUCT_TYPE -> condition instanceof ProductTypeCondition;
            case PRODUCT_LIST -> condition instanceof ProductListCondition;
        };
    }

    private static boolean actionAllowed(Scope scope, Action action) {
        return switch (action) {
            case FixedPriceAction fixed -> priceFieldAllowed(scope, fixed.field());
            case InverseMarginAction margin -> priceFieldAllowed(scope, margin.field());
            case DecimalEndingAction decimal -> priceFieldAllowed(scope, decimal.field());
            case PriceUseModeAction ignored -> scope == Scope.PRICES;
            case OfferAction ignored -> scope != Scope.PRICES;
        };
    }

    private static boolean priceFieldAllowed(Scope scope, PriceField field) {
        return switch (scope) {
            case PRICES -> field != PriceField.OFFER_PRICE;
            case OFFER, STOCK -> field == PriceField.OFFER_PRICE;
            case SUPPLIER, FAMILY, PRODUCT_TYPE, PRODUCT_LIST -> true;
        };
    }

    private static boolean requiresSelector(Scope scope) {
        return scope != Scope.PRICES && scope != Scope.OFFER;
    }

    private static boolean selectsScope(Scope scope, Condition condition) {
        return switch (scope) {
            case PRICES, OFFER -> true;
            case STOCK -> isStockCondition(condition);
            case SUPPLIER -> isReferenceCondition(condition, ReferenceField.SUPPLIER);
            case FAMILY -> isReferenceCondition(condition, ReferenceField.FAMILY)
                    || isReferenceCondition(condition, ReferenceField.SUBFAMILY);
            case PRODUCT_TYPE -> condition instanceof ProductTypeCondition;
            case PRODUCT_LIST -> condition instanceof ProductListCondition;
        };
    }

    private static boolean isCostCondition(Condition condition) {
        return condition instanceof NumericCondition numeric
                && (numeric.field() == NumericField.GROSS_COST
                        || numeric.field() == NumericField.NET_COST);
    }

    private static boolean isStockCondition(Condition condition) {
        return condition instanceof NumericCondition numeric
                && (numeric.field() == NumericField.LOCAL_STOCK
                        || numeric.field() == NumericField.TOTAL_STOCK);
    }

    private static boolean isReferenceCondition(Condition condition, ReferenceField field) {
        return condition instanceof ReferenceCondition reference && reference.field() == field;
    }

    private static String requiredSelectorName(Scope scope) {
        return switch (scope) {
            case STOCK -> "una condicion LOCAL_STOCK o TOTAL_STOCK";
            case SUPPLIER -> "una condicion de proveedor";
            case FAMILY -> "una condicion de familia o subfamilia";
            case PRODUCT_TYPE -> "una condicion de tipo de producto";
            case PRODUCT_LIST -> "una condicion de lista de productos";
            case PRICES, OFFER -> "una condicion valida";
        };
    }

    private static String conditionName(Condition condition) {
        return switch (condition) {
            case NumericCondition numeric -> "NUMBER." + numeric.field();
            case ReferenceCondition reference -> "REFERENCE." + reference.field();
            case ProductTypeCondition ignored -> "PRODUCT_TYPE";
            case ProductListCondition ignored -> "PRODUCT_LIST";
        };
    }

    private static String actionName(Action action) {
        return switch (action) {
            case FixedPriceAction fixed -> "FIXED_PRICE." + fixed.field();
            case InverseMarginAction margin -> "INVERSE_MARGIN." + margin.field();
            case DecimalEndingAction decimal -> "DECIMAL_ENDING." + decimal.field();
            case PriceUseModeAction ignored -> "PRICE_USE_MODE";
            case OfferAction ignored -> "OFFER";
        };
    }

    private static void nonNegative(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(field + " no puede ser negativo");
        }
    }

    private static <T> List<T> immutableValues(List<T> values, String field, int maxSize) {
        return immutableValues(values, field, maxSize, false);
    }

    private static <T> List<T> immutableValues(
            List<T> values, String field, int maxSize, boolean allowEmpty) {
        if (values == null || (!allowEmpty && values.isEmpty())) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        if (values.size() > maxSize) {
            throw new IllegalArgumentException(field + " no puede superar " + maxSize + " elementos");
        }
        List<T> copy = new ArrayList<>(values.size());
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, field + " no admite valores nulos"));
        }
        return List.copyOf(copy);
    }
}
