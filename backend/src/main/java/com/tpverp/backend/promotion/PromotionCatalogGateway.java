package com.tpverp.backend.promotion;

import com.tpverp.backend.catalog.FamilyRepository;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.StoreTax;
import com.tpverp.backend.catalog.StoreTaxRepository;
import com.tpverp.backend.catalog.SubfamilyRepository;
import com.tpverp.backend.document.DocumentLineCommand;
import com.tpverp.backend.document.DocumentLineType;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PromotionCatalogGateway {

    private final ProductRepository products;
    private final StoreTaxRepository taxes;
    private final FamilyRepository families;
    private final SubfamilyRepository subfamilies;

    public PromotionCatalogGateway(
            ProductRepository products,
            StoreTaxRepository taxes,
            FamilyRepository families,
            SubfamilyRepository subfamilies) {
        this.products = products;
        this.taxes = taxes;
        this.families = families;
        this.subfamilies = subfamilies;
    }

    @Transactional(readOnly = true)
    public Map<UUID, ProductSnapshot> products(UUID storeId, Collection<UUID> productIds) {
        var requestedIds = new HashSet<>(productIds == null ? List.of() : productIds);
        if (requestedIds.contains(null)) {
            throw new IllegalArgumentException("productoId es obligatorio");
        }
        if (requestedIds.isEmpty()) {
            return Map.of();
        }
        var productsById = products.findAllByStoreIdAndIdIn(storeId, requestedIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        requireExactIds(requestedIds, productsById.keySet(), "Producto no encontrado en la tienda");

        var taxIds = productsById.values().stream()
                .map(Product::getTaxId)
                .collect(Collectors.toSet());
        if (taxIds.contains(null)) {
            throw new IllegalStateException("Producto sin impuesto configurado");
        }
        var taxesById = taxes.findAllById(taxIds).stream()
                .collect(Collectors.toMap(StoreTax::getId, Function.identity()));
        requireExactIds(taxIds, taxesById.keySet(), "Impuesto de producto no encontrado");

        var result = new LinkedHashMap<UUID, ProductSnapshot>();
        for (var product : productsById.values()) {
            var tax = taxesById.get(product.getTaxId());
            if (!storeId.equals(tax.getStoreId())) {
                throw new IllegalArgumentException("El impuesto del producto no pertenece a la tienda");
            }
            tax.requireSelectable();
            result.put(product.getId(), new ProductSnapshot(product, tax));
        }
        return Map.copyOf(result);
    }

    @Transactional(readOnly = true)
    public void validateTargets(UUID storeId, Collection<TargetReference> targets) {
        var values = List.copyOf(targets == null ? List.of() : targets);
        var productIds = targetIds(values, PromotionTargetType.PRODUCT);
        if (!productIds.isEmpty()) {
            var found = products.findAllByStoreIdAndIdIn(storeId, productIds).stream()
                    .map(Product::getId)
                    .collect(Collectors.toSet());
            requireExactIds(productIds, found, "Target de producto no encontrado en la tienda");
        }

        var subfamilyIds = targetIds(values, PromotionTargetType.SUBFAMILY);
        var loadedSubfamilies = subfamilyIds.isEmpty()
                ? List.<com.tpverp.backend.catalog.Subfamily>of()
                : subfamilies.findAllById(subfamilyIds);
        var foundSubfamilies = loadedSubfamilies.stream()
                .map(value -> value.getId())
                .collect(Collectors.toSet());
        requireExactIds(subfamilyIds, foundSubfamilies, "Target de subfamilia no encontrado");

        var familyIds = targetIds(values, PromotionTargetType.FAMILY);
        loadedSubfamilies.stream().map(value -> value.getFamilyId()).forEach(familyIds::add);
        if (!familyIds.isEmpty()) {
            var loadedFamilies = families.findAllById(familyIds);
            var foundFamilies = loadedFamilies.stream()
                    .map(value -> value.getId())
                    .collect(Collectors.toSet());
            requireExactIds(familyIds, foundFamilies, "Familia de target no encontrada");
            if (loadedFamilies.stream().anyMatch(value -> !storeId.equals(value.getStoreId()))) {
                throw new IllegalArgumentException("El target no pertenece a la tienda actual");
            }
        }
    }

    private static Set<UUID> targetIds(
            Collection<TargetReference> targets,
            PromotionTargetType type) {
        return targets.stream()
                .filter(target -> target.type() == type)
                .map(TargetReference::targetId)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private static void requireExactIds(
            Set<UUID> expected,
            Set<UUID> actual,
            String message) {
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(message);
        }
    }

    public record TargetReference(PromotionTargetType type, UUID targetId) {

        public TargetReference {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(targetId, "targetId");
        }
    }

    public record ProductSnapshot(Product product, StoreTax tax) {

        public ProductSnapshot {
            Objects.requireNonNull(product, "product");
            Objects.requireNonNull(tax, "tax");
        }

        public DocumentLineCommand authoritativeSnapshot(DocumentLineCommand pricedLine) {
            validateTaxSnapshot(
                    pricedLine.impuestosIncluidos(),
                    pricedLine.porcentajeImpuesto(),
                    pricedLine.regimenImpuesto());
            return new DocumentLineCommand(
                    product.getId(),
                    pricedLine.cantidad(),
                    authoritativeCode(product),
                    product.getName(),
                    pricedLine.tarifa(),
                    pricedLine.precioUnitario(),
                    pricedLine.descuento(),
                    product.isTaxesIncluded(),
                    normalizedTaxRegime(pricedLine.regimenImpuesto()),
                    tax.getPercentage(),
                    DocumentLineType.PRODUCT,
                    null,
                    null,
                    null);
        }

        public void validateTaxSnapshot(
                boolean requestedIncluded,
                BigDecimal requestedPercentage,
                String requestedRegime) {
            if (requestedIncluded != product.isTaxesIncluded()) {
                throw new IllegalArgumentException(
                        "impuestosIncluidos no coincide con el catalogo del producto");
            }
            if (requestedPercentage == null
                    || requestedPercentage.compareTo(tax.getPercentage()) != 0) {
                throw new IllegalArgumentException(
                        "porcentajeImpuesto no coincide con el catalogo del producto");
            }
            normalizedTaxRegime(requestedRegime);
        }

        private static String authoritativeCode(Product product) {
            for (var value : new String[] {
                    product.getCode(), product.getBarcode(), product.getBarcode2()}) {
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
            throw new IllegalStateException("Producto sin codigo fiscalizable");
        }

        private static String normalizedTaxRegime(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("regimenImpuesto es obligatorio");
            }
            var normalized = value.trim().toUpperCase(Locale.ROOT);
            if (!normalized.equals("IVA") && !normalized.equals("IGIC")) {
                throw new IllegalArgumentException("message.document.invalid_tax_regime");
            }
            return normalized;
        }
    }
}
