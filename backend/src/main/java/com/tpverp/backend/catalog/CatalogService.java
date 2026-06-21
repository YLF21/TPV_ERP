package com.tpverp.backend.catalog;

import com.tpverp.backend.inventory.StockLevelRepository;
import com.tpverp.backend.inventory.StockMovementRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Tienda;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {

    private final CurrentOrganization organization;
    private final StoreTaxRepository taxRepository;
    private final WarehouseRepository warehouseRepository;
    private final FamilyRepository familyRepository;
    private final SubfamilyRepository subfamilyRepository;
    private final ProductRepository productRepository;
    private final ProductIdentifierRepository identifierRepository;
    private final StockLevelRepository stockRepository;
    private final StockMovementRepository movementRepository;

    public CatalogService(
            CurrentOrganization organization,
            StoreTaxRepository taxRepository,
            WarehouseRepository warehouseRepository,
            FamilyRepository familyRepository,
            SubfamilyRepository subfamilyRepository,
            ProductRepository productRepository,
            ProductIdentifierRepository identifierRepository,
            StockLevelRepository stockRepository,
            StockMovementRepository movementRepository) {
        this.organization = organization;
        this.taxRepository = taxRepository;
        this.warehouseRepository = warehouseRepository;
        this.familyRepository = familyRepository;
        this.subfamilyRepository = subfamilyRepository;
        this.productRepository = productRepository;
        this.identifierRepository = identifierRepository;
        this.stockRepository = stockRepository;
        this.movementRepository = movementRepository;
    }

    @Transactional(readOnly = true)
    public List<StoreTax> taxes() {
        return taxRepository.findByStoreIdOrderByPorcentaje(currentStore().getId());
    }

    @Transactional(readOnly = true)
    public List<StoreTax> selectableTaxes() {
        return taxes().stream().filter(StoreTax::isActive).toList();
    }

    @Transactional
    public StoreTax createTax(BigDecimal percentage) {
        UUID storeId = currentStore().getId();
        if (taxRepository.findByStoreIdAndPorcentaje(storeId, percentage).isPresent()) {
            throw new IllegalArgumentException("Ya existe ese porcentaje de impuesto");
        }
        return taxRepository.save(new StoreTax(storeId, percentage, false));
    }

    @Transactional
    public StoreTax updateTax(UUID taxId, BigDecimal percentage) {
        StoreTax tax = tax(taxId);
        taxRepository.findByStoreIdAndPorcentaje(tax.getStoreId(), percentage)
                .filter(existing -> !existing.getId().equals(taxId))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("Ya existe ese porcentaje de impuesto");
                });
        tax.replacePercentage(percentage);
        return tax;
    }

    @Transactional
    public void deleteTax(UUID taxId) {
        StoreTax tax = tax(taxId);
        tax.requireDeletable();
        if (productRepository.existsByTaxId(taxId)) {
            throw new IllegalStateException("No se puede eliminar un impuesto utilizado por productos");
        }
        taxRepository.delete(tax);
    }

    @Transactional
    public StoreTax setDefaultTax(UUID taxId) {
        StoreTax selected = tax(taxId);
        taxRepository.findByStoreIdAndPredeterminadoTrue(selected.getStoreId())
                .filter(current -> !current.getId().equals(selected.getId()))
                .ifPresent(StoreTax::clearDefault);
        selected.markDefault();
        return selected;
    }

    @Transactional
    public StoreTax setTaxActive(UUID taxId, boolean active) {
        StoreTax tax = tax(taxId);
        if (active) {
            tax.activate();
        } else {
            tax.deactivate();
        }
        return tax;
    }

    @Transactional(readOnly = true)
    public List<Warehouse> warehouses() {
        return warehouseRepository.findByStoreIdOrderByNombre(currentStore().getId());
    }

    @Transactional
    public Warehouse createWarehouse(String name) {
        UUID storeId = currentStore().getId();
        String normalized = CatalogText.normalized(name, "nombre");
        if (warehouseRepository.existsByStoreIdAndNombreIgnoreCase(storeId, normalized)) {
            throw new IllegalArgumentException("Ya existe un almacen con ese nombre");
        }
        return warehouseRepository.save(new Warehouse(storeId, normalized));
    }

    @Transactional
    public Warehouse renameWarehouse(UUID warehouseId, String name) {
        Warehouse warehouse = warehouse(warehouseId);
        String normalized = CatalogText.normalized(name, "nombre");
        if (!warehouse.getName().equals(normalized)
                && warehouseRepository.existsByStoreIdAndNombreIgnoreCase(warehouse.getStoreId(), normalized)) {
            throw new IllegalArgumentException("Ya existe un almacen con ese nombre");
        }
        warehouse.rename(normalized);
        return warehouse;
    }

    @Transactional
    public Warehouse setWarehouseActive(UUID warehouseId, boolean active) {
        Warehouse warehouse = warehouse(warehouseId);
        if (active) {
            warehouse.activate();
        } else {
            warehouse.deactivate(stockRepository.sumQuantityByWarehouseId(warehouseId));
        }
        return warehouse;
    }

    @Transactional
    public void deleteWarehouse(UUID warehouseId) {
        Warehouse warehouse = warehouse(warehouseId);
        warehouse.deactivate(stockRepository.sumQuantityByWarehouseId(warehouseId));
        warehouseRepository.delete(warehouse);
    }

    @Transactional(readOnly = true)
    public List<Family> families() {
        return familyRepository.findByStoreIdOrderByNombre(currentStore().getId());
    }

    @Transactional
    public Family createFamily(String name) {
        return familyRepository.save(new Family(currentStore().getId(), name, false));
    }

    @Transactional
    public Family renameFamily(UUID familyId, String name) {
        Family family = family(familyId);
        family.rename(name);
        return family;
    }

    @Transactional
    public void deleteFamily(UUID familyId) {
        Family family = family(familyId);
        family.requireDeletable();
        Family general = familyRepository.findByStoreIdAndPredeterminadaTrue(family.getStoreId())
                .orElseThrow(() -> new IllegalStateException("La familia GENERAL no esta inicializada"));
        productRepository.findByFamilyId(familyId).forEach(product -> product.update(
                general.getId(), null, product.getTaxId(), product.getName(), product.getDescription(),
                product.getPurchasePrice(), product.isTaxesIncluded()));
        familyRepository.delete(family);
    }

    @Transactional
    public Subfamily createSubfamily(UUID familyId, String name) {
        family(familyId);
        return subfamilyRepository.save(new Subfamily(familyId, name));
    }

    @Transactional
    public Subfamily renameSubfamily(UUID subfamilyId, String name) {
        Subfamily subfamily = subfamily(subfamilyId);
        subfamily.rename(name);
        return subfamily;
    }

    @Transactional
    public void deleteSubfamily(UUID subfamilyId) {
        Subfamily subfamily = subfamily(subfamilyId);
        productRepository.findByStoreIdOrderByNombre(currentStore().getId()).stream()
                .filter(product -> subfamilyId.equals(product.getSubfamilyId()))
                .forEach(product -> product.update(
                        product.getFamilyId(), null, product.getTaxId(), product.getName(),
                        product.getDescription(), product.getPurchasePrice(), product.isTaxesIncluded()));
        subfamilyRepository.delete(subfamily);
    }

    @Transactional(readOnly = true)
    public List<Product> products() {
        return productRepository.findByStoreIdOrderByNombre(currentStore().getId());
    }

    @Transactional(readOnly = true)
    public Product product(UUID productId) {
        return sameStore(productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Producto no encontrado")));
    }

    @Transactional
    public Product createProduct(ProductRequest request) {
        UUID storeId = currentStore().getId();
        validateReferences(request, storeId);
        validateIdentifiers(storeId, null, request.code(), request.barcode());
        Product product = new Product(
                storeId, request.familyId(), request.subfamilyId(), request.taxId(),
                request.name(), request.description(), request.purchasePrice(), request.taxesIncluded());
        applyProductData(product, request);
        return productRepository.save(product);
    }

    @Transactional
    public Product updateProduct(UUID productId, ProductRequest request) {
        Product product = product(productId);
        validateReferences(request, product.getStoreId());
        validateIdentifiers(product.getStoreId(), productId, request.code(), request.barcode());
        product.update(
                request.familyId(), request.subfamilyId(), request.taxId(), request.name(),
                request.description(), request.purchasePrice(), request.taxesIncluded());
        applyProductData(product, request);
        return product;
    }

    @Transactional
    public void deleteProduct(UUID productId) {
        Product product = product(productId);
        if (stockRepository.existsByProductId(productId) || movementRepository.existsByProductId(productId)) {
            throw new IllegalStateException("No se puede eliminar un producto con historial");
        }
        productRepository.delete(product);
    }

    private void applyProductData(Product product, ProductRequest request) {
        product.replaceIdentifier(IdentifierType.CODIGO, request.code());
        if (request.barcode() != null && !request.barcode().isBlank()) {
            product.replaceIdentifier(IdentifierType.CODIGO_BARRAS, request.barcode());
        } else {
            product.removeIdentifier(IdentifierType.CODIGO_BARRAS);
        }
        product.setPrice(PriceTier.VENTA, request.salePrice());
        product.setPrice(PriceTier.SOCIO, request.memberPrice());
        product.setPrice(PriceTier.MAYORISTA, request.wholesalePrice());
        product.setPrice(PriceTier.OFERTA, request.offerPrice());
        product.configureOffer(request.offerActive(), request.offerFrom(), request.offerUntil());
    }

    private void validateReferences(ProductRequest request, UUID storeId) {
        Family family = family(request.familyId());
        if (!family.getStoreId().equals(storeId)) {
            throw new IllegalArgumentException("La familia no pertenece a la tienda");
        }
        if (request.subfamilyId() != null) {
            Subfamily subfamily = subfamily(request.subfamilyId());
            if (!subfamily.getFamilyId().equals(request.familyId())) {
                throw new IllegalArgumentException("La subfamilia no pertenece a la familia");
            }
        }
        StoreTax tax = tax(request.taxId());
        if (!tax.getStoreId().equals(storeId)) {
            throw new IllegalArgumentException("El impuesto no pertenece a la tienda");
        }
        tax.requireSelectable();
    }

    private void validateIdentifiers(UUID storeId, UUID productId, String... values) {
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            boolean collision = productId == null
                    ? identifierRepository.findByStoreIdAndValor(storeId, normalized).isPresent()
                    : identifierRepository.existsByStoreIdAndValorAndProductIdNot(storeId, normalized, productId);
            if (collision) {
                throw new IllegalArgumentException("El identificador ya esta asignado a otro producto");
            }
        }
    }

    private StoreTax tax(UUID id) {
        return sameStore(taxRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Impuesto no encontrado")));
    }

    private Warehouse warehouse(UUID id) {
        return sameStore(warehouseRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Almacen no encontrado")));
    }

    private Family family(UUID id) {
        return sameStore(familyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Familia no encontrada")));
    }

    private Subfamily subfamily(UUID id) {
        Subfamily value = subfamilyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Subfamilia no encontrada"));
        family(value.getFamilyId());
        return value;
    }

    private <T> T sameStore(T value) {
        UUID current = currentStore().getId();
        UUID owner = switch (value) {
            case StoreTax tax -> tax.getStoreId();
            case Warehouse warehouse -> warehouse.getStoreId();
            case Family family -> family.getStoreId();
            case Product product -> product.getStoreId();
            default -> throw new IllegalArgumentException("Tipo de catalogo no soportado");
        };
        if (!Objects.equals(current, owner)) {
            throw new IllegalArgumentException("El recurso no pertenece a la tienda actual");
        }
        return value;
    }

    private Tienda currentStore() {
        return organization.currentStore();
    }

    public record ProductRequest(
            @NotNull UUID familyId,
            UUID subfamilyId,
            @NotNull UUID taxId,
            @NotBlank String name,
            String description,
            @NotNull BigDecimal purchasePrice,
            boolean taxesIncluded,
            @NotBlank String code,
            String barcode,
            @NotNull BigDecimal salePrice,
            BigDecimal memberPrice,
            BigDecimal wholesalePrice,
            BigDecimal offerPrice,
            boolean offerActive,
            LocalDate offerFrom,
            LocalDate offerUntil) {

        public ProductRequest {
        }
    }
}
