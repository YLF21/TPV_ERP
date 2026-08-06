package com.tpverp.backend.catalog;

import com.tpverp.backend.installation.InstallationStatusService;
import com.tpverp.backend.licensing.License;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.licensing.application.TaxRegime;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.shared.access.OperationalMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SaleProductCatalogService {

    private final CatalogService catalog;
    private final StoreTaxRepository taxes;
    private final LicenseRepository licenses;
    private final InstallationStatusService installationStatus;
    private final CurrentOrganization organization;
    private final ProductIdentifierRepository identifiers;
    private final ProductRepository products;
    private final Clock clock;

    public SaleProductCatalogService(
            CatalogService catalog,
            StoreTaxRepository taxes,
            LicenseRepository licenses,
            InstallationStatusService installationStatus,
            CurrentOrganization organization,
            ProductIdentifierRepository identifiers,
            ProductRepository products,
            Clock clock) {
        this.catalog = catalog;
        this.taxes = taxes;
        this.licenses = licenses;
        this.installationStatus = installationStatus;
        this.organization = organization;
        this.identifiers = identifiers;
        this.products = products;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<SaleProductView> products() {
        UUID storeId = organization.currentStore().getId();
        List<Product> products = catalog.products();
        List<UUID> taxIds = products.stream()
                .map(Product::getTaxId)
                .distinct()
                .toList();
        if (taxIds.contains(null)) {
            throw new IllegalStateException("Producto sin impuesto configurado");
        }

        Map<UUID, StoreTax> taxesById = taxes.findAllById(taxIds).stream()
                .collect(Collectors.toMap(StoreTax::getId, Function.identity()));
        TaxRegime taxRegime = currentTaxRegime(storeId);
        if (taxRegime == null) {
            throw new IllegalStateException("Licencia sin regimen fiscal configurado");
        }

        return products.stream()
                .map(product -> saleView(product, storeId, taxesById, taxRegime.name()))
                .toList();
    }

    @Transactional(readOnly = true)
    public SalePriceConsultationView priceByIdentifier(String rawIdentifier) {
        String identifier = rawIdentifier == null ? "" : rawIdentifier.trim();
        if (identifier.isEmpty()) {
            throw new IllegalArgumentException("message.product.code_or_barcode_required");
        }

        var store = organization.currentStore();
        UUID storeId = store.getId();
        List<UUID> productIds = identifiers.findAllByStoreIdAndValor(storeId, identifier).stream()
                .map(ProductIdentifier::getProductId)
                .distinct()
                .toList();
        if (productIds.size() > 1) {
            throw new IllegalStateException("Identificador asignado a varios productos");
        }
        Product product = productIds.stream()
                .findFirst()
                .flatMap(products::findById)
                .filter(value -> storeId.equals(value.getStoreId()))
                .filter(Product::isActive)
                .orElseThrow(NoSuchElementException::new);

        LocalDate businessDate = LocalDate.now(clock.withZone(ZoneId.of(store.getTimezone())));
        PriceUseMode activePriceType = activePriceType(product, businessDate);
        return new SalePriceConsultationView(
                product.getId(),
                product.getCode(),
                product.getName(),
                product.getImageId() != null,
                product.getSalePrice(),
                activePriceType,
                activePriceType == PriceUseMode.MEMBER_PRICE ? product.getMemberPrice() : null,
                activePriceType == PriceUseMode.OFFER_PRICE ? product.getOfferPrice() : null,
                activePriceType == PriceUseMode.OFFER_DISCOUNT ? product.getOfferDiscountPercent() : null,
                activePriceType == PriceUseMode.OFFER_PRICE
                        || activePriceType == PriceUseMode.OFFER_DISCOUNT
                        ? product.getOfferUntil() : null);
    }

    private static PriceUseMode activePriceType(Product product, LocalDate businessDate) {
        PriceUseMode mode = product.getPriceUseMode() == null ? PriceUseMode.NORMAL : product.getPriceUseMode();
        if (mode == PriceUseMode.MEMBER_PRICE) {
            return product.getMemberPrice() == null ? PriceUseMode.NORMAL : mode;
        }
        if (mode != PriceUseMode.OFFER_PRICE && mode != PriceUseMode.OFFER_DISCOUNT) {
            return PriceUseMode.NORMAL;
        }
        boolean current = product.isOfferActive()
                && product.getOfferFrom() != null
                && !businessDate.isBefore(product.getOfferFrom())
                && (product.getOfferUntil() == null || !businessDate.isAfter(product.getOfferUntil()));
        if (!current) {
            return PriceUseMode.NORMAL;
        }
        if (mode == PriceUseMode.OFFER_PRICE && product.getOfferPrice() == null) {
            return PriceUseMode.NORMAL;
        }
        if (mode == PriceUseMode.OFFER_DISCOUNT && product.getOfferDiscountPercent() == null) {
            return PriceUseMode.NORMAL;
        }
        return mode;
    }

    private TaxRegime currentTaxRegime(UUID storeId) {
        License license = licenses.findByTiendaIdOrderByValidaDesdeDesc(storeId).stream()
                .filter(License::isActiva)
                .findFirst()
                .orElse(null);
        if (license == null) {
            if (installationStatus.status().mode() == OperationalMode.DEVELOPMENT) {
                return TaxRegime.IVA;
            }
            throw new IllegalStateException("No hay licencia activa para la tienda");
        }
        if (!storeId.equals(license.getTiendaId())) {
            throw new IllegalArgumentException("La licencia no pertenece a la tienda actual");
        }
        return license.getRegimenImpuesto();
    }

    private static SaleProductView saleView(
            Product product,
            UUID storeId,
            Map<UUID, StoreTax> taxesById,
            String taxRegime) {
        if (!storeId.equals(product.getStoreId())) {
            throw new IllegalArgumentException("Producto no pertenece a la tienda actual");
        }
        StoreTax tax = taxesById.get(product.getTaxId());
        if (tax == null) {
            throw new IllegalArgumentException("Impuesto de producto no encontrado");
        }
        if (!storeId.equals(tax.getStoreId())) {
            throw new IllegalArgumentException("El impuesto del producto no pertenece a la tienda");
        }
        tax.requireSelectable();
        return new SaleProductView(
                product.getId(),
                product.getImageId(),
                product.isActive(),
                product.getProductType(),
                product.getCode(),
                product.getBarcode(),
                product.getBarcode2(),
                product.getName(),
                product.getSalePrice(),
                product.getMemberPrice(),
                product.getOfferPrice(),
                product.getOfferDiscountPercent(),
                product.getPriceUseMode(),
                product.getDiscountType(),
                product.isOfferActive(),
                product.getOfferFrom(),
                product.getOfferUntil(),
                product.isTaxesIncluded(),
                product.getTaxId(),
                tax.getPercentage(),
                taxRegime,
                product.getPackageQuantity());
    }
}
