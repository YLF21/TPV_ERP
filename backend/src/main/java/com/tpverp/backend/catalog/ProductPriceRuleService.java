package com.tpverp.backend.catalog;

import com.tpverp.backend.inventory.StockLevel;
import com.tpverp.backend.inventory.StockLevelRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserAccountRepository;
import jakarta.persistence.OptimisticLockException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductPriceRuleService {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final CurrentOrganization organization;
    private final UserAccountRepository users;
    private final ProductPriceRuleRepository repository;
    private final ProductRepository products;
    private final ProductSupplierRepository productSuppliers;
    private final StockLevelRepository stockLevels;
    private final WarehouseRepository warehouses;
    private final CatalogService catalog;
    private final Clock clock;

    public ProductPriceRuleService(
            CurrentOrganization organization,
            UserAccountRepository users,
            ProductPriceRuleRepository repository,
            ProductRepository products,
            ProductSupplierRepository productSuppliers,
            StockLevelRepository stockLevels,
            WarehouseRepository warehouses,
            CatalogService catalog,
            Clock clock) {
        this.organization = organization;
        this.users = users;
        this.repository = repository;
        this.products = products;
        this.productSuppliers = productSuppliers;
        this.stockLevels = stockLevels;
        this.warehouses = warehouses;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ProductPriceRuleView> list() {
        UUID companyId = organization.currentCompany().getId();
        Map<UUID, UserAccount> userIndex = userIndex(companyId);
        return repository.findByCompanyIdOrderByUpdatedAtDesc(companyId).stream()
                .map(rule -> view(rule, userIndex))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProductPriceRuleView get(UUID id) {
        UUID companyId = organization.currentCompany().getId();
        return view(find(id, companyId), userIndex(companyId));
    }

    @Transactional
    public ProductPriceRuleView create(
            ProductPriceRuleCreateRequest request,
            Authentication authentication) {
        UUID companyId = organization.currentCompany().getId();
        UserAccount user = organization.currentUser(authentication);
        ProductPriceRule rule = new ProductPriceRule(
                companyId,
                request.name(),
                request.forms(),
                user.getId(),
                clock.instant());
        repository.saveAndFlush(rule);
        return view(rule, userIndex(companyId));
    }

    @Transactional
    public ProductPriceRuleView update(
            UUID id,
            ProductPriceRuleUpdateRequest request,
            Authentication authentication) {
        UUID companyId = organization.currentCompany().getId();
        organization.currentUser(authentication);
        ProductPriceRule rule = find(id, companyId);
        requireVersion(rule, request.version());
        rule.update(request.name(), request.forms(), clock.instant());
        flushWithOptimisticConflict(rule, request.version());
        return view(rule, userIndex(companyId));
    }

    @Transactional
    public void delete(UUID id, long version, Authentication authentication) {
        UUID companyId = organization.currentCompany().getId();
        UserAccount user = organization.currentUser(authentication);
        ProductPriceRule rule = find(id, companyId);
        requireVersion(rule, version);
        if (!rule.getCreatedBy().equals(user.getId()) && !isAdmin(authentication)) {
            throw new AccessDeniedException("Solo ADMIN o el creador pueden eliminar la regla");
        }
        try {
            repository.delete(rule);
            repository.flush();
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException exception) {
            throw staleVersion(id, version, null);
        }
    }

    @Transactional(readOnly = true)
    public ProductPriceRulePreview preview(UUID id, Long ruleVersion, List<UUID> productIds) {
        UUID companyId = organization.currentCompany().getId();
        ProductPriceRule rule = find(id, companyId);
        requireVersion(rule, ruleVersion);
        return evaluate(rule, productIds).preview();
    }

    private Evaluation evaluate(ProductPriceRule rule, List<UUID> requestedProductIds) {
        UUID storeId = organization.currentStore().getId();
        List<ProductPriceRuleForm.Definition> forms =
                ProductPriceRuleForm.validateAndCopy(rule.getForms());
        List<Product> productList = selectedProducts(storeId, requestedProductIds);
        EvaluationContext context = context(forms, storeId, productList);
        List<ProductPriceRulePreview.ProductChange> changedProducts = new ArrayList<>();
        int matchedProducts = 0;

        for (Product product : productList) {
            ProductEvaluation productEvaluation = evaluateProduct(forms, product, context);
            if (productEvaluation.matched()) {
                matchedProducts++;
            }
            if (!productEvaluation.changes().isEmpty()) {
                catalog.validateProductUpdate(product.getId(), productEvaluation.request());
                changedProducts.add(new ProductPriceRulePreview.ProductChange(
                        product.getId(), product.getName(), productEvaluation.changes()));
            }
        }
        return new Evaluation(new ProductPriceRulePreview(
                rule.getId(), rule.getVersion(), matchedProducts, List.copyOf(changedProducts)));
    }

    private List<Product> selectedProducts(UUID storeId, List<UUID> requestedProductIds) {
        if (requestedProductIds == null || requestedProductIds.isEmpty()) {
            throw new IllegalArgumentException("Selecciona al menos un producto para ejecutar la regla");
        }
        LinkedHashSet<UUID> targetIds = new LinkedHashSet<>(requestedProductIds);
        List<Product> found = products.findAllByStoreIdAndIdIn(storeId, targetIds);
        Map<UUID, Product> productsById = found.stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        if (productsById.size() != targetIds.size()) {
            throw new IllegalArgumentException(
                    "La regla contiene productos no encontrados en la tienda actual");
        }
        return targetIds.stream().map(productsById::get).toList();
    }

    private ProductEvaluation evaluateProduct(
            List<ProductPriceRuleForm.Definition> forms,
            Product product,
            EvaluationContext context) {
        ProductState original = ProductState.from(product);
        EnumMap<ProductPriceRulePreview.Field, Assignment> assignments =
                new EnumMap<>(ProductPriceRulePreview.Field.class);
        boolean matched = false;

        for (int index = 0; index < forms.size(); index++) {
            ProductPriceRuleForm.Definition form = forms.get(index);
            if (!matches(form, product, context)) {
                continue;
            }
            matched = true;
            EnumMap<ProductPriceRulePreview.Field, Object> formAssignments =
                    applyActions(form, original.copy(), product);
            mergeAssignments(assignments, formAssignments, index, product);
        }

        if (assignments.isEmpty()) {
            return new ProductEvaluation(matched, null, List.of());
        }

        ProductState result = original.copy();
        assignments.forEach((field, assignment) -> result.set(field, assignment.value()));
        reconcileOfferDiscount(result, assignments, product);
        result.offerActive = isOfferMode(result.priceUseMode) || result.offerActive;
        CatalogService.ProductRequest request = result.request();

        List<ProductPriceRulePreview.FieldChange> changes = new ArrayList<>();
        for (ProductPriceRulePreview.Field field : ProductPriceRulePreview.Field.values()) {
            Object before = original.value(field);
            Object after = field == ProductPriceRulePreview.Field.DISCOUNT_TYPE
                    ? request.discountType()
                    : result.value(field);
            if (!sameValue(before, after)) {
                Assignment source = assignments.get(field);
                if (field == ProductPriceRulePreview.Field.DISCOUNT_TYPE) {
                    source = assignments.get(ProductPriceRulePreview.Field.PRICE_USE_MODE);
                }
                changes.add(new ProductPriceRulePreview.FieldChange(
                        field,
                        before,
                        after,
                        source == null ? List.of() : List.copyOf(source.formIndexes())));
            }
        }
        return new ProductEvaluation(matched, request, List.copyOf(changes));
    }

    private static EnumMap<ProductPriceRulePreview.Field, Object> applyActions(
            ProductPriceRuleForm.Definition form,
            ProductState state,
            Product product) {
        EnumMap<ProductPriceRulePreview.Field, Object> values =
                new EnumMap<>(ProductPriceRulePreview.Field.class);
        applyAutomaticOfferMode(form.actions(), state, values);
        List<ProductPriceRuleForm.Action> orderedActions = form.actions().stream()
                .sorted(Comparator.comparingInt(ProductPriceRuleService::actionPhase))
                .toList();
        for (ProductPriceRuleForm.Action action : orderedActions) {
            switch (action) {
                case ProductPriceRuleForm.FixedPriceAction fixed -> {
                    ProductPriceRulePreview.Field field = field(fixed.field());
                    state.set(field, money(fixed.value()));
                    values.put(field, state.value(field));
                    if (field == ProductPriceRulePreview.Field.SALE_PRICE) {
                        refreshOfferPrice(state, values);
                    }
                }
                case ProductPriceRuleForm.InverseMarginAction margin -> {
                    ProductPriceRulePreview.Field field = field(margin.field());
                    BigDecimal cost = margin.costBasis() == ProductPriceRuleForm.CostBasis.GROSS
                            ? state.grossCost()
                            : state.netCost();
                    BigDecimal denominator = ONE_HUNDRED
                            .subtract(margin.marginPercent())
                            .movePointLeft(2);
                    if (denominator.signum() <= 0) {
                        throw new IllegalArgumentException(
                                "El margen inverso produce un denominador no positivo");
                    }
                    state.set(field, cost.divide(denominator, 2, RoundingMode.HALF_UP));
                    values.put(field, state.value(field));
                    if (field == ProductPriceRulePreview.Field.SALE_PRICE) {
                        refreshOfferPrice(state, values);
                    }
                }
                case ProductPriceRuleForm.DecimalEndingAction ending -> {
                    ProductPriceRulePreview.Field field = field(ending.field());
                    BigDecimal current = (BigDecimal) state.value(field);
                    if (current == null) {
                        throw new IllegalArgumentException(
                                "El producto " + product.getId() + " no tiene valor para " + field);
                    }
                    BigDecimal adjusted = current.setScale(0, RoundingMode.DOWN)
                            .add(BigDecimal.valueOf(ending.cents(), 2));
                    state.set(field, money(adjusted));
                    values.put(field, state.value(field));
                    if (field == ProductPriceRulePreview.Field.SALE_PRICE) {
                        refreshOfferPrice(state, values);
                    }
                }
                case ProductPriceRuleForm.PriceUseModeAction priceUse -> {
                    state.priceUseMode = priceUse.value();
                    state.offerActive = isOfferMode(priceUse.value());
                    values.put(ProductPriceRulePreview.Field.PRICE_USE_MODE, priceUse.value());
                    values.put(ProductPriceRulePreview.Field.OFFER_ACTIVE, state.offerActive);
                    refreshOfferPrice(state, values);
                }
                case ProductPriceRuleForm.OfferAction offer -> {
                    state.offerActive = offer.active();
                    state.offerDiscountPercent = percentage(offer.discountPercent());
                    state.offerFrom = offer.from();
                    state.offerUntil = offer.until();
                    values.put(ProductPriceRulePreview.Field.OFFER_ACTIVE, state.offerActive);
                    values.put(ProductPriceRulePreview.Field.OFFER_DISCOUNT_PERCENT,
                            state.offerDiscountPercent);
                    values.put(ProductPriceRulePreview.Field.OFFER_FROM, state.offerFrom);
                    values.put(ProductPriceRulePreview.Field.OFFER_UNTIL, state.offerUntil);
                    refreshOfferPrice(state, values);
                }
            }
        }
        return values;
    }

    private static int actionPhase(ProductPriceRuleForm.Action action) {
        return switch (action) {
            case ProductPriceRuleForm.FixedPriceAction ignored -> 0;
            case ProductPriceRuleForm.InverseMarginAction ignored -> 0;
            case ProductPriceRuleForm.DecimalEndingAction ignored -> 1;
            case ProductPriceRuleForm.PriceUseModeAction ignored -> 2;
            case ProductPriceRuleForm.OfferAction ignored -> 3;
        };
    }

    private static void applyAutomaticOfferMode(
            List<ProductPriceRuleForm.Action> actions,
            ProductState state,
            EnumMap<ProductPriceRulePreview.Field, Object> values) {
        PriceUseMode mode = automaticOfferMode(actions);
        if (mode == null) {
            return;
        }
        state.priceUseMode = mode;
        state.offerActive = isOfferMode(mode);
        values.put(ProductPriceRulePreview.Field.PRICE_USE_MODE, mode);
        values.put(ProductPriceRulePreview.Field.OFFER_ACTIVE, state.offerActive);
        if (mode != PriceUseMode.OFFER_DISCOUNT) {
            state.offerDiscountPercent = null;
            values.put(ProductPriceRulePreview.Field.OFFER_DISCOUNT_PERCENT, null);
        }
    }

    private static PriceUseMode automaticOfferMode(List<ProductPriceRuleForm.Action> actions) {
        ProductPriceRuleForm.OfferAction offer = actions.stream()
                .filter(ProductPriceRuleForm.OfferAction.class::isInstance)
                .map(ProductPriceRuleForm.OfferAction.class::cast)
                .findFirst()
                .orElse(null);
        boolean changesOfferPrice = actions.stream().anyMatch(action -> switch (action) {
            case ProductPriceRuleForm.FixedPriceAction fixed ->
                    fixed.field() == ProductPriceRuleForm.PriceField.OFFER_PRICE;
            case ProductPriceRuleForm.InverseMarginAction margin ->
                    margin.field() == ProductPriceRuleForm.PriceField.OFFER_PRICE;
            case ProductPriceRuleForm.DecimalEndingAction decimal ->
                    decimal.field() == ProductPriceRuleForm.PriceField.OFFER_PRICE;
            case ProductPriceRuleForm.PriceUseModeAction ignored -> false;
            case ProductPriceRuleForm.OfferAction ignored -> false;
        });
        if (offer == null) {
            return changesOfferPrice ? PriceUseMode.OFFER_PRICE : null;
        }
        if (!offer.active()) {
            return PriceUseMode.NORMAL;
        }
        return offer.discountPercent() == null
                ? PriceUseMode.OFFER_PRICE
                : PriceUseMode.OFFER_DISCOUNT;
    }

    private static void refreshOfferPrice(
            ProductState state,
            EnumMap<ProductPriceRulePreview.Field, Object> assignments) {
        if (state.priceUseMode != PriceUseMode.OFFER_DISCOUNT
                || state.salePrice == null
                || state.offerDiscountPercent == null) {
            return;
        }
        state.offerPrice = offerPrice(state.salePrice, state.offerDiscountPercent);
        assignments.put(ProductPriceRulePreview.Field.OFFER_PRICE, state.offerPrice);
    }

    private static void reconcileOfferDiscount(
            ProductState state,
            EnumMap<ProductPriceRulePreview.Field, Assignment> assignments,
            Product product) {
        if (state.priceUseMode != PriceUseMode.OFFER_DISCOUNT
                || state.salePrice == null
                || state.offerDiscountPercent == null) {
            return;
        }
        Set<Integer> sources = new LinkedHashSet<>();
        addSources(sources, assignments.get(ProductPriceRulePreview.Field.SALE_PRICE));
        addSources(sources, assignments.get(ProductPriceRulePreview.Field.PRICE_USE_MODE));
        addSources(sources, assignments.get(ProductPriceRulePreview.Field.OFFER_DISCOUNT_PERCENT));
        if (sources.isEmpty()) {
            return;
        }
        BigDecimal derived = offerPrice(state.salePrice, state.offerDiscountPercent);
        Assignment current = assignments.get(ProductPriceRulePreview.Field.OFFER_PRICE);
        if (current != null && !sameValue(current.value(), derived)) {
            Set<Integer> conflictSources = new LinkedHashSet<>(current.formIndexes());
            conflictSources.addAll(sources);
            if (conflictSources.size() > 1) {
                throw new ProductPriceRuleConflictException(
                        product.getId(),
                        product.getName(),
                        ProductPriceRulePreview.Field.OFFER_PRICE,
                        List.copyOf(conflictSources));
            }
            return;
        }
        if (current == null) {
            assignments.put(
                    ProductPriceRulePreview.Field.OFFER_PRICE,
                    new Assignment(derived, sources));
        } else {
            current.formIndexes().addAll(sources);
        }
        state.offerPrice = derived;
    }

    private static void mergeAssignments(
            EnumMap<ProductPriceRulePreview.Field, Assignment> assignments,
            EnumMap<ProductPriceRulePreview.Field, Object> formAssignments,
            int formIndex,
            Product product) {
        formAssignments.forEach((field, value) -> {
            Assignment current = assignments.get(field);
            if (current != null && !sameValue(current.value(), value)) {
                Set<Integer> indexes = new LinkedHashSet<>(current.formIndexes());
                indexes.add(formIndex);
                throw new ProductPriceRuleConflictException(
                        product.getId(), product.getName(), field, List.copyOf(indexes));
            }
            if (current == null) {
                assignments.put(field, new Assignment(value, new LinkedHashSet<>(List.of(formIndex))));
            } else {
                current.formIndexes().add(formIndex);
            }
        });
    }

    private static boolean matches(
            ProductPriceRuleForm.Definition form,
            Product product,
            EvaluationContext context) {
        return form.conditions().stream().allMatch(condition -> switch (condition) {
            case ProductPriceRuleForm.NumericCondition numeric ->
                    matchesNumeric(numeric, product, context);
            case ProductPriceRuleForm.ReferenceCondition reference ->
                    matchesReference(reference, product, context);
            case ProductPriceRuleForm.ProductTypeCondition type ->
                    membership(type.comparator(), type.values().contains(product.getProductType()));
            case ProductPriceRuleForm.ProductListCondition list ->
                    membership(list.comparator(), list.productIds().contains(product.getId()));
        });
    }

    private static boolean matchesNumeric(
            ProductPriceRuleForm.NumericCondition condition,
            Product product,
            EvaluationContext context) {
        BigDecimal actual = switch (condition.field()) {
            case GROSS_COST -> product.getPurchasePrice();
            case NET_COST -> netCost(product);
            case LOCAL_STOCK -> context.localStock(product.getId(), condition.warehouseId());
            case TOTAL_STOCK -> context.totalStock(product.getId());
        };
        int comparison = actual.compareTo(condition.value());
        return switch (condition.comparator()) {
            case EQ -> comparison == 0;
            case NE -> comparison != 0;
            case GT -> comparison > 0;
            case GTE -> comparison >= 0;
            case LT -> comparison < 0;
            case LTE -> comparison <= 0;
        };
    }

    private static boolean matchesReference(
            ProductPriceRuleForm.ReferenceCondition condition,
            Product product,
            EvaluationContext context) {
        boolean contained = switch (condition.field()) {
            case FAMILY -> condition.values().contains(product.getFamilyId());
            case SUBFAMILY -> product.getSubfamilyId() != null
                    && condition.values().contains(product.getSubfamilyId());
            case SUPPLIER -> context.supplierIds(product.getId()).stream()
                    .anyMatch(condition.values()::contains);
        };
        return membership(condition.comparator(), contained);
    }

    private EvaluationContext context(
            List<ProductPriceRuleForm.Definition> forms,
            UUID storeId,
            List<Product> productList) {
        boolean needsSuppliers = forms.stream()
                .flatMap(form -> form.conditions().stream())
                .anyMatch(condition -> condition instanceof ProductPriceRuleForm.ReferenceCondition reference
                        && reference.field() == ProductPriceRuleForm.ReferenceField.SUPPLIER);
        List<ProductPriceRuleForm.NumericCondition> stockConditions = forms.stream()
                .flatMap(form -> form.conditions().stream())
                .filter(ProductPriceRuleForm.NumericCondition.class::isInstance)
                .map(ProductPriceRuleForm.NumericCondition.class::cast)
                .filter(condition -> condition.field() == ProductPriceRuleForm.NumericField.LOCAL_STOCK
                        || condition.field() == ProductPriceRuleForm.NumericField.TOTAL_STOCK)
                .toList();

        Map<UUID, Set<UUID>> suppliersByProduct = new HashMap<>();
        if (needsSuppliers && !productList.isEmpty()) {
            List<UUID> productIds = productList.stream().map(Product::getId).toList();
            for (ProductSupplier link : productSuppliers.findForProducts(storeId, productIds)) {
                suppliersByProduct.computeIfAbsent(link.getProductId(), ignored -> new LinkedHashSet<>())
                        .add(link.getSupplier().getId());
            }
        }

        Map<UUID, List<StockLevel>> stockByProduct = new HashMap<>();
        if (!stockConditions.isEmpty()) {
            for (Product product : productList) {
                stockByProduct.put(product.getId(), stockLevels.findByProductId(product.getId()));
            }
        }

        UUID defaultWarehouseId = null;
        boolean needsDefaultWarehouse = stockConditions.stream()
                .anyMatch(condition -> condition.field() == ProductPriceRuleForm.NumericField.LOCAL_STOCK
                        && condition.warehouseId() == null);
        if (needsDefaultWarehouse) {
            defaultWarehouseId = warehouses.findByStoreIdAndPredeterminadoTrue(storeId)
                    .map(Warehouse::getId)
                    .orElse(null);
        }
        Set<UUID> explicitWarehouses = stockConditions.stream()
                .map(ProductPriceRuleForm.NumericCondition::warehouseId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (!explicitWarehouses.isEmpty()) {
            Set<UUID> storeWarehouses = warehouses.findByStoreIdOrderByNombre(storeId).stream()
                    .map(Warehouse::getId)
                    .collect(Collectors.toSet());
            if (!storeWarehouses.containsAll(explicitWarehouses)) {
                throw new IllegalArgumentException(
                        "Una condicion LOCAL_STOCK referencia un almacen ajeno a la tienda actual");
            }
        }
        return new EvaluationContext(
                suppliersByProduct, stockByProduct, defaultWarehouseId);
    }

    private ProductPriceRule find(UUID id, UUID companyId) {
        return repository.findByIdAndCompanyId(id, companyId)
                .orElseThrow(() -> new IllegalArgumentException("Regla de precio no encontrada"));
    }

    private Map<UUID, UserAccount> userIndex(UUID companyId) {
        return users.findAllByEmpresaIdOrderByNombre(companyId).stream()
                .collect(Collectors.toMap(UserAccount::getId, Function.identity()));
    }

    private static ProductPriceRuleView view(
            ProductPriceRule rule,
            Map<UUID, UserAccount> userIndex) {
        UserAccount creator = userIndex.get(rule.getCreatedBy());
        return new ProductPriceRuleView(
                rule.getId(),
                rule.getName(),
                rule.getForms(),
                rule.getCreatedBy(),
                creator == null ? rule.getCreatedBy().toString() : creator.getUserName(),
                rule.getCreatedAt(),
                rule.getUpdatedAt(),
                rule.getVersion());
    }

    private void flushWithOptimisticConflict(ProductPriceRule rule, long expectedVersion) {
        try {
            repository.saveAndFlush(rule);
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException exception) {
            throw staleVersion(rule.getId(), expectedVersion, null);
        }
    }

    private static void requireVersion(ProductPriceRule rule, Long expectedVersion) {
        if (expectedVersion == null) {
            throw new IllegalArgumentException("version es obligatoria");
        }
        if (rule.getVersion() != expectedVersion) {
            throw staleVersion(rule.getId(), expectedVersion, rule.getVersion());
        }
    }

    private static IllegalStateException staleVersion(UUID id, long expected, Long actual) {
        String detail = actual == null ? "ya fue modificada" : "tiene version " + actual;
        return new IllegalStateException(
                "Conflicto de version en la regla " + id + ": se esperaba " + expected + " y " + detail);
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority())
                        || "ADMIN".equals(authority.getAuthority()));
    }

    private static ProductPriceRulePreview.Field field(ProductPriceRuleForm.PriceField field) {
        return switch (field) {
            case SALE_PRICE -> ProductPriceRulePreview.Field.SALE_PRICE;
            case MEMBER_PRICE -> ProductPriceRulePreview.Field.MEMBER_PRICE;
            case WHOLESALE_PRICE -> ProductPriceRulePreview.Field.WHOLESALE_PRICE;
            case OFFER_PRICE -> ProductPriceRulePreview.Field.OFFER_PRICE;
        };
    }

    private static boolean membership(ProductPriceRuleForm.SetComparator comparator, boolean contained) {
        return comparator == ProductPriceRuleForm.SetComparator.IN ? contained : !contained;
    }

    private static BigDecimal netCost(Product product) {
        BigDecimal discount = Optional.ofNullable(product.getPurchaseDiscountPercent())
                .orElse(BigDecimal.ZERO);
        return product.getPurchasePrice()
                .multiply(BigDecimal.ONE.subtract(discount.movePointLeft(2)))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal value) {
        return Objects.requireNonNull(value, "value").setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal percentage(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal offerPrice(BigDecimal salePrice, BigDecimal discountPercent) {
        return salePrice.subtract(salePrice.multiply(discountPercent)
                        .divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP))
                .max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static boolean isOfferMode(PriceUseMode mode) {
        return mode == PriceUseMode.OFFER_PRICE || mode == PriceUseMode.OFFER_DISCOUNT;
    }

    private static boolean sameValue(Object first, Object second) {
        if (first instanceof BigDecimal left && second instanceof BigDecimal right) {
            return left.compareTo(right) == 0;
        }
        return Objects.equals(first, second);
    }

    private static void addSources(Set<Integer> target, Assignment assignment) {
        if (assignment != null) {
            target.addAll(assignment.formIndexes());
        }
    }

    public record ProductPriceRuleCreateRequest(
            @NotBlank @Size(max = 160) String name,
            @NotEmpty @Valid List<ProductPriceRuleForm.Definition> forms) {
    }

    public record ProductPriceRuleUpdateRequest(
            @NotNull Long version,
            @NotBlank @Size(max = 160) String name,
            @NotEmpty @Valid List<ProductPriceRuleForm.Definition> forms) {
    }

    public record ProductPriceRuleExecutionRequest(
            @NotNull Long ruleVersion,
            @NotEmpty @Size(max = 5_000) List<@NotNull UUID> productIds) {
    }

    private record Assignment(Object value, Set<Integer> formIndexes) {
    }

    private record Evaluation(ProductPriceRulePreview preview) {
    }

    private record ProductEvaluation(
            boolean matched,
            CatalogService.ProductRequest request,
            List<ProductPriceRulePreview.FieldChange> changes) {
    }

    private record EvaluationContext(
            Map<UUID, Set<UUID>> suppliersByProduct,
            Map<UUID, List<StockLevel>> stockByProduct,
            UUID defaultWarehouseId) {

        Set<UUID> supplierIds(UUID productId) {
            return suppliersByProduct.getOrDefault(productId, Set.of());
        }

        BigDecimal localStock(UUID productId, UUID warehouseId) {
            UUID effectiveWarehouse = warehouseId == null ? defaultWarehouseId : warehouseId;
            if (effectiveWarehouse == null) {
                return BigDecimal.ZERO;
            }
            return stockByProduct.getOrDefault(productId, List.of()).stream()
                    .filter(stock -> effectiveWarehouse.equals(stock.getWarehouseId()))
                    .map(StockLevel::getQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        BigDecimal totalStock(UUID productId) {
            return stockByProduct.getOrDefault(productId, List.of()).stream()
                    .map(StockLevel::getQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    private static final class ProductState {

        private final Product product;
        private BigDecimal salePrice;
        private BigDecimal memberPrice;
        private BigDecimal wholesalePrice;
        private BigDecimal offerPrice;
        private PriceUseMode priceUseMode;
        private DiscountType discountType;
        private BigDecimal offerDiscountPercent;
        private boolean offerActive;
        private LocalDate offerFrom;
        private LocalDate offerUntil;

        private ProductState(Product product) {
            this.product = product;
            salePrice = product.getSalePrice();
            memberPrice = product.getMemberPrice();
            wholesalePrice = product.getWholesalePrice();
            offerPrice = product.getOfferPrice();
            priceUseMode = product.getPriceUseMode();
            discountType = product.getDiscountType();
            offerDiscountPercent = product.getOfferDiscountPercent();
            offerActive = product.isOfferActive();
            offerFrom = product.getOfferFrom();
            offerUntil = product.getOfferUntil();
        }

        private ProductState(ProductState source) {
            product = source.product;
            salePrice = source.salePrice;
            memberPrice = source.memberPrice;
            wholesalePrice = source.wholesalePrice;
            offerPrice = source.offerPrice;
            priceUseMode = source.priceUseMode;
            discountType = source.discountType;
            offerDiscountPercent = source.offerDiscountPercent;
            offerActive = source.offerActive;
            offerFrom = source.offerFrom;
            offerUntil = source.offerUntil;
        }

        static ProductState from(Product product) {
            return new ProductState(product);
        }

        ProductState copy() {
            return new ProductState(this);
        }

        BigDecimal grossCost() {
            return product.getPurchasePrice();
        }

        BigDecimal netCost() {
            return ProductPriceRuleService.netCost(product);
        }

        Object value(ProductPriceRulePreview.Field field) {
            return switch (field) {
                case SALE_PRICE -> salePrice;
                case MEMBER_PRICE -> memberPrice;
                case WHOLESALE_PRICE -> wholesalePrice;
                case OFFER_PRICE -> offerPrice;
                case PRICE_USE_MODE -> priceUseMode;
                case DISCOUNT_TYPE -> discountType;
                case OFFER_DISCOUNT_PERCENT -> offerDiscountPercent;
                case OFFER_ACTIVE -> offerActive;
                case OFFER_FROM -> offerFrom;
                case OFFER_UNTIL -> offerUntil;
            };
        }

        void set(ProductPriceRulePreview.Field field, Object value) {
            switch (field) {
                case SALE_PRICE -> salePrice = (BigDecimal) value;
                case MEMBER_PRICE -> memberPrice = (BigDecimal) value;
                case WHOLESALE_PRICE -> wholesalePrice = (BigDecimal) value;
                case OFFER_PRICE -> offerPrice = (BigDecimal) value;
                case PRICE_USE_MODE -> priceUseMode = (PriceUseMode) value;
                case DISCOUNT_TYPE -> discountType = (DiscountType) value;
                case OFFER_DISCOUNT_PERCENT -> offerDiscountPercent = (BigDecimal) value;
                case OFFER_ACTIVE -> offerActive = (Boolean) value;
                case OFFER_FROM -> offerFrom = (LocalDate) value;
                case OFFER_UNTIL -> offerUntil = (LocalDate) value;
            }
        }

        CatalogService.ProductRequest request() {
            return new CatalogService.ProductRequest(
                    product.getFamilyId(),
                    product.getSubfamilyId(),
                    product.getTaxId(),
                    product.getProductType(),
                    discountType,
                    priceUseMode,
                    product.getName(),
                    product.getDescription(),
                    product.getComments(),
                    product.getPurchasePrice(),
                    product.isTaxesIncluded(),
                    product.getCode(),
                    product.getBarcode(),
                    product.getBarcode2(),
                    salePrice,
                    memberPrice,
                    wholesalePrice,
                    offerPrice,
                    offerDiscountPercent,
                    product.getPurchaseDiscountPercent(),
                    offerActive,
                    offerFrom,
                    offerUntil);
        }
    }
}
