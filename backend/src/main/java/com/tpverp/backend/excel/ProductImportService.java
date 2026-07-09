package com.tpverp.backend.excel;

import com.tpverp.backend.catalog.CatalogService;
import com.tpverp.backend.catalog.CatalogService.ProductRequest;
import com.tpverp.backend.catalog.DiscountType;
import com.tpverp.backend.catalog.FamilyRepository;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductIdentifier;
import com.tpverp.backend.catalog.ProductIdentifierRepository;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.ProductType;
import com.tpverp.backend.catalog.PriceUseMode;
import com.tpverp.backend.catalog.StoreTax;
import com.tpverp.backend.catalog.StoreTaxRepository;
import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.DocumentCommand;
import com.tpverp.backend.document.DocumentLineCommand;
import com.tpverp.backend.document.DocumentService;
import com.tpverp.backend.organization.CurrentOrganization;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductImportService {

    private final CurrentOrganization organization;
    private final ProductIdentifierRepository identifiers;
    private final ProductRepository products;
    private final CatalogService catalogService;
    private final DocumentService documentService;
    private final FamilyRepository families;
    private final StoreTaxRepository taxes;
    private final ProductImportLineMetadataRepository lineMetadata;

    public ProductImportService(
            CurrentOrganization organization,
            ProductIdentifierRepository identifiers,
            ProductRepository products,
            CatalogService catalogService,
            DocumentService documentService,
            FamilyRepository families,
            StoreTaxRepository taxes,
            ProductImportLineMetadataRepository lineMetadata) {
        this.organization = organization;
        this.identifiers = identifiers;
        this.products = products;
        this.catalogService = catalogService;
        this.documentService = documentService;
        this.families = families;
        this.taxes = taxes;
        this.lineMetadata = lineMetadata;
    }

    @Transactional(readOnly = true)
    public ProductImportPreview preview(InputStream input, ProductImportMapping mapping) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(mapping, "mapping");
        var storeId = organization.currentStore().getId();
        var rows = new ArrayList<ProductImportPreviewRow>();
        try (var workbook = WorkbookFactory.create(input)) {
            var sheet = workbook.getSheetAt(0);
            for (int index = mapping.firstRowIndex(); index <= sheet.getLastRowNum(); index++) {
                var row = sheet.getRow(index);
                if (isEmpty(row, mapping)) {
                    continue;
                }
                rows.add(previewRow(storeId, index + 1, row, mapping));
            }
        }
        return new ProductImportPreview(List.copyOf(rows));
    }

    @Transactional(rollbackFor = Exception.class)
    public CommercialDocument confirm(
            InputStream input,
            ProductImportConfirmRequest request,
            Authentication authentication) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(request.mapping(), "mapping");
        requirePurchaseDocumentType(request.documentType());
        var bytes = input.readAllBytes();
        var preview = preview(new ByteArrayInputStream(bytes), request.mapping());
        if (preview.rows().stream().anyMatch(row -> row.status() == ProductImportPreviewRow.Status.ERROR)) {
            throw new IllegalArgumentException("la importacion contiene errores");
        }

        var storeId = organization.currentStore().getId();
        var lines = new ArrayList<DocumentLineCommand>();
        var metadataDrafts = new ArrayList<ProductImportLineMetadataDraft>();
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            for (int index = request.mapping().firstRowIndex(); index <= sheet.getLastRowNum(); index++) {
                var row = sheet.getRow(index);
                if (isEmpty(row, request.mapping())) {
                    continue;
                }
                var errors = new ArrayList<String>();
                var existing = existingProduct(
                        storeId,
                        ExcelCellReader.text(row, request.mapping().codigo()),
                        ExcelCellReader.text(row, request.mapping().codigoBarras()),
                        errors);
                if (!errors.isEmpty()) {
                    throw new IllegalArgumentException(String.join(", ", errors));
                }
                var tax = resolveTax(storeId, row, request.mapping(), existing);
                var productRequest = productRequest(row, request.mapping(), existing, tax);
                var saved = catalogService.createOrUpdateFromImport(
                        productRequest,
                        existing.map(Product::getId).orElse(null));
                var quantity = quantity(row, request.mapping().cantidad());
                if (quantity != null && quantity > 0) {
                    metadataDrafts.add(new ProductImportLineMetadataDraft(
                            saved.getId(),
                            supplierReference(row, request.mapping(), saved)));
                    lines.add(new DocumentLineCommand(
                            saved.getId(),
                            quantity,
                            saved.getCode(),
                            saved.getName(),
                            null,
                            linePurchasePrice(row, request.mapping(), saved),
                            BigDecimal.ZERO,
                            saved.isTaxesIncluded(),
                            "GENERAL",
                            tax.getPercentage()));
                }
            }
        }
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("la importacion necesita al menos una linea con cantidad");
        }
        var command = new DocumentCommand(
                request.warehouseId(),
                request.documentType(),
                request.date() == null ? LocalDate.now() : request.date(),
                null,
                request.supplierId(),
                request.externalNumber(),
                BigDecimal.ZERO,
                false,
                List.copyOf(lines));
        var document = request.documentType() == CommercialDocumentType.ALBARAN_COMPRA
                ? documentService.createDeliveryNote(command, authentication)
                : documentService.createInvoice(command, authentication);
        saveLineMetadata(document.getId(), metadataDrafts);
        return document;
    }

    private void saveLineMetadata(UUID documentId, List<ProductImportLineMetadataDraft> metadataDrafts) {
        if (metadataDrafts.isEmpty()) {
            return;
        }
        lineMetadata.saveAll(metadataDrafts.stream()
                .map(draft -> new ProductImportLineMetadata(
                        documentId, draft.productId(), draft.supplierReference()))
                .toList());
    }

    private ProductImportPreviewRow previewRow(
            UUID storeId,
            int rowNumber,
            Row row,
            ProductImportMapping mapping) {
        var errors = new ArrayList<String>();
        var code = ExcelCellReader.text(row, mapping.codigo());
        var barcode = ExcelCellReader.text(row, mapping.codigoBarras());
        var name = ExcelCellReader.text(row, mapping.nombre());
        var description = ExcelCellReader.text(row, mapping.descripcion());
        validateTax(storeId, row, mapping, errors);
        validateQuantity(row, mapping.cantidad(), errors);

        var product = existingProduct(storeId, code, barcode, errors);
        var purchasePrice = price(row, mapping.precioCompra(), "precioCompra",
                shouldReadPurchasePrice(product, mapping), BigDecimal.ZERO, errors);
        var salePrice = price(row, mapping.precioVenta(), "precioVenta",
                shouldReadSalePrice(product, mapping), BigDecimal.ZERO, errors);
        var wholesalePrice = price(row, mapping.precioMayorista(), "precioMayorista",
                shouldReadWholesalePrice(product, mapping), new BigDecimal("0.01"), errors);
        var memberPrice = price(row, mapping.precioMiembro(), "precioMiembro",
                shouldReadMemberPrice(product, mapping), new BigDecimal("0.01"), errors);
        if (product.isEmpty()) {
            validateNewProduct(code, barcode, name, purchasePrice, errors);
        }
        if (!errors.isEmpty()) {
            return new ProductImportPreviewRow(rowNumber,
                    ProductImportPreviewRow.Status.ERROR,
                    product.map(Product::getId).orElse(null),
                    List.copyOf(errors),
                    List.of());
        }
        if (product.isEmpty()) {
            return new ProductImportPreviewRow(rowNumber,
                    ProductImportPreviewRow.Status.NEW_PRODUCT,
                    null,
                    List.of(),
                    List.of());
        }
        var changes = changes(product.get(), mapping, name, description,
                purchasePrice, salePrice, wholesalePrice, memberPrice);
        var status = changes.isEmpty()
                ? ProductImportPreviewRow.Status.PRODUCT_ONLY
                : ProductImportPreviewRow.Status.UPDATE_PRODUCT;
        return new ProductImportPreviewRow(rowNumber,
                status,
                product.get().getId(),
                List.of(),
                changes);
    }

    private ProductRequest productRequest(
            Row row,
            ProductImportMapping mapping,
            Optional<Product> existing,
            StoreTax tax) {
        if (existing.isEmpty()) {
            return newProductRequest(row, mapping, tax);
        }
        var product = existing.get();
        var name = valueOrCurrent(mapping.updateName(), ExcelCellReader.text(row, mapping.nombre()),
                product.getName());
        var description = valueOrCurrent(mapping.updateDescription(),
                ExcelCellReader.text(row, mapping.descripcion()), product.getDescription());
        var purchasePrice = moneyOrCurrent(mapping.updatePurchasePrice(),
                money(row, mapping.precioCompra()), product.getPurchasePrice());
        var salePrice = moneyOrCurrent(mapping.updateSalePrice(),
                money(row, mapping.precioVenta()), product.getSalePrice());
        var wholesalePrice = moneyOrCurrent(mapping.updateWholesalePrice(),
                money(row, mapping.precioMayorista()), product.getWholesalePrice());
        var memberPrice = moneyOrCurrent(mapping.updateMemberPrice(),
                money(row, mapping.precioMiembro()), product.getMemberPrice());
        return new ProductRequest(
                product.getFamilyId(),
                product.getSubfamilyId(),
                product.getTaxId(),
                product.getProductType(),
                product.getDiscountType(),
                product.getPriceUseMode(),
                name,
                description,
                product.getComments(),
                purchasePrice,
                product.isTaxesIncluded(),
                product.getCode(),
                product.getBarcode(),
                salePrice,
                memberPrice,
                wholesalePrice,
                product.getOfferPrice(),
                product.getOfferDiscountPercent(),
                product.isOfferActive(),
                product.getOfferFrom(),
                product.getOfferUntil());
    }

    private ProductRequest newProductRequest(Row row, ProductImportMapping mapping, StoreTax tax) {
        var barcode = ExcelCellReader.text(row, mapping.codigoBarras());
        var code = ExcelCellReader.text(row, mapping.codigo());
        if (isBlank(code)) {
            code = barcode;
        }
        var family = families.findByStoreIdAndPredeterminadaTrue(tax.getStoreId())
                .orElseThrow(() -> new IllegalStateException("La familia GENERAL no esta inicializada"));
        var salePrice = money(row, mapping.precioVenta());
        return new ProductRequest(
                family.getId(),
                null,
                tax.getId(),
                ProductType.UNIT,
                DiscountType.NORMAL,
                PriceUseMode.NORMAL,
                ExcelCellReader.text(row, mapping.nombre()),
                ExcelCellReader.text(row, mapping.descripcion()),
                null,
                money(row, mapping.precioCompra()),
                true,
                code,
                barcode,
                salePrice == null ? BigDecimal.ZERO : salePrice,
                money(row, mapping.precioMiembro()),
                money(row, mapping.precioMayorista()),
                null,
                null,
                false,
                null,
                null);
    }

    private StoreTax resolveTax(
            UUID storeId,
            Row row,
            ProductImportMapping mapping,
            Optional<Product> existing) {
        var percentage = percentage(row, mapping.impuesto(), new ArrayList<>());
        var tax = percentage == null && existing.isPresent()
                ? taxes.findById(existing.get().getTaxId())
                : percentage == null
                        ? taxes.findByStoreIdAndPredeterminadoTrue(storeId)
                        : taxes.findByStoreIdAndPorcentaje(storeId, percentage);
        var resolved = tax.orElseThrow(() -> new IllegalArgumentException("impuesto no encontrado"));
        resolved.requireSelectable();
        return resolved;
    }

    private static void requirePurchaseDocumentType(CommercialDocumentType type) {
        if (type != CommercialDocumentType.ALBARAN_COMPRA
                && type != CommercialDocumentType.FACTURA_COMPRA) {
            throw new IllegalArgumentException("tipo de documento de importacion no permitido");
        }
    }

    private static String valueOrCurrent(boolean enabled, String value, String current) {
        return enabled && value != null ? value : current;
    }

    private static BigDecimal moneyOrCurrent(boolean enabled, BigDecimal value, BigDecimal current) {
        return enabled && value != null ? value : current;
    }

    private static BigDecimal money(Row row, String column) {
        return ExcelCellReader.text(row, column) == null ? null : ExcelCellReader.money(row, column);
    }

    private static Integer quantity(Row row, String column) {
        return ExcelCellReader.text(row, column) == null ? null : ExcelCellReader.integer(row, column);
    }

    private static BigDecimal linePurchasePrice(Row row, ProductImportMapping mapping, Product saved) {
        var excel = money(row, mapping.precioCompra());
        return excel == null ? saved.getPurchasePrice() : excel;
    }

    private static String supplierReference(Row row, ProductImportMapping mapping, Product saved) {
        var reference = ExcelCellReader.text(row, mapping.referenciaProveedor());
        return reference == null ? saved.getCode() : reference;
    }

    private void validateTax(
            UUID storeId,
            Row row,
            ProductImportMapping mapping,
            List<String> errors) {
        if (ExcelCellReader.text(row, mapping.impuesto()) == null) {
            return;
        }
        var before = errors.size();
        var value = percentage(row, mapping.impuesto(), errors);
        if (errors.size() != before) {
            return;
        }
        var tax = taxes.findByStoreIdAndPorcentaje(storeId, value);
        if (tax.isEmpty()) {
            errors.add("impuesto no encontrado");
            return;
        }
        if (!tax.get().isActive()) {
            errors.add("impuesto no activo");
        }
    }

    private Optional<Product> existingProduct(
            UUID storeId,
            String code,
            String barcode,
            List<String> errors) {
        var codeProductId = findProductId(storeId, code);
        var barcodeProductId = findProductId(storeId, barcode);
        if (codeProductId.isPresent()
                && barcodeProductId.isPresent()
                && !codeProductId.get().equals(barcodeProductId.get())) {
            errors.add("codigo y codigoBarras pertenecen a productos distintos");
            return Optional.empty();
        }
        var productId = codeProductId.or(() -> barcodeProductId);
        if (productId.isEmpty()) {
            return Optional.empty();
        }
        var product = products.findById(productId.get());
        if (product.isEmpty()) {
            errors.add("producto existente no encontrado");
        }
        return product;
    }

    private Optional<UUID> findProductId(UUID storeId, String value) {
        var normalized = normalizeIdentifier(value);
        if (normalized == null) {
            return Optional.empty();
        }
        return identifiers.findByStoreIdAndValor(storeId, normalized)
                .map(ProductIdentifier::getProductId);
    }

    private static void validateNewProduct(
            String code,
            String barcode,
            String name,
            BigDecimal purchasePrice,
            List<String> errors) {
        if (isBlank(code) && isBlank(barcode)) {
            errors.add("codigo o codigoBarras es obligatorio");
        }
        if (isBlank(name)) {
            errors.add("nombre es obligatorio");
        }
        if (purchasePrice == null) {
            errors.add("precioCompra es obligatorio");
        }
    }

    private static void validateQuantity(Row row, String column, List<String> errors) {
        if (ExcelCellReader.text(row, column) == null) {
            return;
        }
        try {
            var quantity = ExcelCellReader.integer(row, column);
            if (quantity <= 0) {
                errors.add("cantidad debe ser positiva");
            }
        } catch (ArithmeticException | NumberFormatException exception) {
            errors.add("cantidad no valida");
        }
    }

    private static BigDecimal price(
            Row row,
            String column,
            String field,
            boolean read,
            BigDecimal minimum,
            List<String> errors) {
        if (!read || ExcelCellReader.text(row, column) == null) {
            return null;
        }
        try {
            var value = ExcelCellReader.money(row, column);
            if (value.compareTo(minimum) < 0) {
                errors.add(minimum.signum() == 0
                        ? field + " no puede ser negativo"
                        : field + " debe ser mayor o igual a " + format(minimum));
            }
            return value;
        } catch (NumberFormatException exception) {
            errors.add(field + " no valido");
            return null;
        }
    }

    private static BigDecimal percentage(Row row, String column, List<String> errors) {
        if (ExcelCellReader.text(row, column) == null) {
            return null;
        }
        try {
            var value = ExcelCellReader.money(row, column);
            if (value.compareTo(BigDecimal.ZERO) < 0
                    || value.compareTo(new BigDecimal("100")) > 0) {
                errors.add("impuesto debe estar entre 0 y 100");
            }
            return value;
        } catch (NumberFormatException exception) {
            errors.add("impuesto no valido");
            return null;
        }
    }

    private static List<ProductImportPreviewRow.ProductChange> changes(
            Product product,
            ProductImportMapping mapping,
            String name,
            String description,
            BigDecimal purchasePrice,
            BigDecimal salePrice,
            BigDecimal wholesalePrice,
            BigDecimal memberPrice) {
        var changes = new ArrayList<ProductImportPreviewRow.ProductChange>();
        addTextChange(changes, mapping.updateName(), "nombre", product.getName(), name);
        addTextChange(changes, mapping.updateDescription(), "descripcion",
                product.getDescription(), description);
        if (mapping.updatePurchasePrice()) {
            addPurchasePriceChange(changes, product, purchasePrice);
        }
        if (mapping.updateSalePrice()) {
            addSalePriceChange(changes, product, salePrice);
        }
        if (mapping.updateWholesalePrice()) {
            addWholesalePriceChange(changes, product, wholesalePrice);
        }
        if (mapping.updateMemberPrice()) {
            addMemberPriceChange(changes, product, memberPrice);
        }
        return List.copyOf(changes);
    }

    private static void addTextChange(
            List<ProductImportPreviewRow.ProductChange> changes,
            boolean enabled,
            String field,
            String current,
            String excel) {
        if (!enabled || excel == null || Objects.equals(current, excel)) {
            return;
        }
        changes.add(new ProductImportPreviewRow.ProductChange(field, current, excel));
    }

    private static void addPurchasePriceChange(
            List<ProductImportPreviewRow.ProductChange> changes,
            Product product,
            BigDecimal excel) {
        addMoneyChange(changes, "precioCompra", product.getPurchasePrice(), excel);
    }

    private static void addSalePriceChange(
            List<ProductImportPreviewRow.ProductChange> changes,
            Product product,
            BigDecimal excel) {
        addMoneyChange(changes, "precioVenta", product.getSalePrice(), excel);
    }

    private static void addWholesalePriceChange(
            List<ProductImportPreviewRow.ProductChange> changes,
            Product product,
            BigDecimal excel) {
        addMoneyChange(changes, "precioMayorista", product.getWholesalePrice(), excel);
    }

    private static void addMemberPriceChange(
            List<ProductImportPreviewRow.ProductChange> changes,
            Product product,
            BigDecimal excel) {
        addMoneyChange(changes, "precioMiembro", product.getMemberPrice(), excel);
    }

    private static void addMoneyChange(
            List<ProductImportPreviewRow.ProductChange> changes,
            String field,
            BigDecimal current,
            BigDecimal excel) {
        if (excel == null || sameMoney(current, excel)) {
            return;
        }
        changes.add(new ProductImportPreviewRow.ProductChange(field,
                format(current),
                format(excel)));
    }

    private static boolean sameMoney(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.compareTo(right) == 0;
    }

    private static String format(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static boolean shouldReadPurchasePrice(Optional<Product> product, ProductImportMapping mapping) {
        return product.isEmpty() || mapping.updatePurchasePrice();
    }

    private static boolean shouldReadSalePrice(Optional<Product> product, ProductImportMapping mapping) {
        return product.isEmpty() || mapping.updateSalePrice();
    }

    private static boolean shouldReadWholesalePrice(Optional<Product> product, ProductImportMapping mapping) {
        return product.isEmpty() || mapping.updateWholesalePrice();
    }

    private static boolean shouldReadMemberPrice(Optional<Product> product, ProductImportMapping mapping) {
        return product.isEmpty() || mapping.updateMemberPrice();
    }

    private static boolean isEmpty(Row row, ProductImportMapping mapping) {
        return ExcelCellReader.text(row, mapping.codigo()) == null
                && ExcelCellReader.text(row, mapping.codigoBarras()) == null
                && ExcelCellReader.text(row, mapping.nombre()) == null
                && ExcelCellReader.text(row, mapping.descripcion()) == null
                && ExcelCellReader.text(row, mapping.precioCompra()) == null
                && ExcelCellReader.text(row, mapping.precioVenta()) == null
                && ExcelCellReader.text(row, mapping.precioMayorista()) == null
                && ExcelCellReader.text(row, mapping.precioMiembro()) == null
                && ExcelCellReader.text(row, mapping.impuesto()) == null
                && ExcelCellReader.text(row, mapping.cantidad()) == null
                && ExcelCellReader.text(row, mapping.referenciaProveedor()) == null;
    }

    private static String normalizeIdentifier(String value) {
        if (isBlank(value)) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record ProductImportLineMetadataDraft(UUID productId, String supplierReference) {
    }
}
