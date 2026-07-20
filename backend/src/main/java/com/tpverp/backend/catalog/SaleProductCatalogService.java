package com.tpverp.backend.catalog;

import com.tpverp.backend.licensing.License;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.licensing.application.TaxRegime;
import com.tpverp.backend.organization.CurrentOrganization;
import java.util.List;
import java.util.Map;
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
    private final CurrentOrganization organization;

    public SaleProductCatalogService(
            CatalogService catalog,
            StoreTaxRepository taxes,
            LicenseRepository licenses,
            CurrentOrganization organization) {
        this.catalog = catalog;
        this.taxes = taxes;
        this.licenses = licenses;
        this.organization = organization;
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
        TaxRegime taxRegime = activeLicense(storeId).getRegimenImpuesto();
        if (taxRegime == null) {
            throw new IllegalStateException("Licencia sin regimen fiscal configurado");
        }

        return products.stream()
                .map(product -> saleView(product, storeId, taxesById, taxRegime.name()))
                .toList();
    }

    private License activeLicense(UUID storeId) {
        License license = licenses.findByTiendaIdOrderByValidaDesdeDesc(storeId).stream()
                .filter(License::isActiva)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No hay licencia activa para la tienda"));
        if (!storeId.equals(license.getTiendaId())) {
            throw new IllegalArgumentException("La licencia no pertenece a la tienda actual");
        }
        return license;
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
                product.isActive(),
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
                taxRegime);
    }
}
