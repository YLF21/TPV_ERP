package com.tpverp.backend.excel;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductIdentifier;
import com.tpverp.backend.catalog.ProductIdentifierRepository;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;

@Service
public class ProductImportService {

    private final CurrentOrganization organization;
    private final ProductIdentifierRepository identifiers;
    private final ProductRepository products;

    public ProductImportService(
            CurrentOrganization organization,
            ProductIdentifierRepository identifiers,
            ProductRepository products) {
        this.organization = organization;
        this.identifiers = identifiers;
        this.products = products;
    }

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
        var purchasePrice = money(row, mapping.precioCompra(), "precioCompra", errors);
        var salePrice = money(row, mapping.precioVenta(), "precioVenta", errors);
        var wholesalePrice = money(row, mapping.precioMayorista(), "precioMayorista", errors);
        var memberPrice = money(row, mapping.precioMiembro(), "precioMiembro", errors);
        percentage(row, mapping.impuesto(), errors);
        validateQuantity(row, mapping.cantidad(), errors);

        var product = existingProduct(storeId, code, barcode, errors);
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

    private static BigDecimal money(Row row, String column, String field, List<String> errors) {
        if (ExcelCellReader.text(row, column) == null) {
            return null;
        }
        try {
            return ExcelCellReader.money(row, column);
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
            return ExcelCellReader.money(row, column);
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
        addMoneyChange(changes, mapping.updatePurchasePrice(), "precioCompra",
                product.getPurchasePrice(), purchasePrice);
        addMoneyChange(changes, mapping.updateSalePrice(), "precioVenta",
                product.getSalePrice(), salePrice);
        addMoneyChange(changes, mapping.updateWholesalePrice(), "precioMayorista",
                product.getWholesalePrice(), wholesalePrice);
        addMoneyChange(changes, mapping.updateMemberPrice(), "precioMiembro",
                product.getMemberPrice(), memberPrice);
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

    private static void addMoneyChange(
            List<ProductImportPreviewRow.ProductChange> changes,
            boolean enabled,
            String field,
            BigDecimal current,
            BigDecimal excel) {
        if (!enabled || excel == null || sameMoney(current, excel)) {
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
}
