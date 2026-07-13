package com.tpverp.backend.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ProductBulkEditContent {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final int MAX_ROWS = 5_000;
    private static final int MAX_SUPPLIERS_PER_ROW = 100;
    private static final int MAX_TOTAL_SUPPLIERS = 20_000;
    private static final long MAX_ESTIMATED_JSON_CHARACTERS = 8L * 1024 * 1024;

    private ProductBulkEditContent() {
    }

    public static List<Row> validateAndCopy(List<Row> rows) {
        if (rows == null) {
            throw new IllegalArgumentException("content es obligatorio");
        }
        if (rows.size() > MAX_ROWS) {
            throw new IllegalArgumentException("content no puede superar " + MAX_ROWS + " filas");
        }
        Set<String> rowIds = new HashSet<>();
        Set<UUID> productIds = new HashSet<>();
        int totalSuppliers = 0;
        long estimatedJsonCharacters = 2;
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            if (row == null) {
                throw invalid(index, "la fila no puede ser nula");
            }
            if (!rowIds.add(row.id())) {
                throw invalid(index, "id de fila duplicado: " + row.id());
            }
            if (row.suppliers().size() > MAX_SUPPLIERS_PER_ROW) {
                throw invalid(index, "suppliers no puede superar "
                        + MAX_SUPPLIERS_PER_ROW + " elementos");
            }
            totalSuppliers += row.suppliers().size() + (row.pendingSupplier() == null ? 0 : 1);
            if (totalSuppliers > MAX_TOTAL_SUPPLIERS) {
                throw new IllegalArgumentException(
                        "content no puede superar " + MAX_TOTAL_SUPPLIERS + " proveedores");
            }
            estimatedJsonCharacters += estimatedSize(row);
            if (estimatedJsonCharacters > MAX_ESTIMATED_JSON_CHARACTERS) {
                throw new IllegalArgumentException(
                        "content supera el limite de 8 MB de JSON");
            }
            validateSupplier(row.pendingSupplier(), index, "pendingSupplier");
            for (SupplierData supplier : row.suppliers()) {
                if (supplier == null) {
                    throw invalid(index, "suppliers no admite valores nulos");
                }
                validateSupplier(supplier, index, "suppliers");
            }
            if (row.product() == null) {
                continue;
            }
            if (!productIds.add(row.product().productId())) {
                throw invalid(index, "producto duplicado: " + row.product().productId());
            }
            validateProduct(row.effectiveProduct(), index);
        }
        return List.copyOf(rows);
    }

    private static void validateProduct(ProductData product, int rowIndex) {
        if (product == null || product.productId() == null) {
            throw invalid(rowIndex, "productId es obligatorio");
        }
        if (product.version() == null || product.version() < 0) {
            throw invalid(rowIndex, "version es obligatoria y no puede ser negativa");
        }
        required(product.name(), rowIndex, "name");
        uuid(product.familyId(), rowIndex, "familyId", true);
        uuid(product.taxId(), rowIndex, "taxId", true);
        uuid(product.subfamilyId(), rowIndex, "subfamilyId", false);
        uuid(product.warehouseId(), rowIndex, "warehouseId", false);
        enumValue(product.productType(), ProductType.class, rowIndex, "productType");
        if (blank(product.code()) && blank(product.barcode())) {
            throw invalid(rowIndex, "code o barcode es obligatorio");
        }
        nonNegative(product.purchasePrice(), rowIndex, "purchasePrice", true);
        nonNegative(product.salePrice(), rowIndex, "salePrice", true);
        nonNegative(product.memberPrice(), rowIndex, "memberPrice", false);
        nonNegative(product.wholesalePrice(), rowIndex, "wholesalePrice", false);
        nonNegative(product.offerPrice(), rowIndex, "offerPrice", false);
        percentage(product.purchaseDiscountPercent(), rowIndex, "purchaseDiscountPercent");
        percentage(product.offerDiscountPercent(), rowIndex, "offerDiscountPercent");
        date(product.offerFrom(), rowIndex, "offerFrom");
        date(product.offerUntil(), rowIndex, "offerUntil");

        String priceUse = normalized(product.discountType());
        if (priceUse != null && !Set.of("NORMAL", "MEMBER_PRICE", "OFFER_PRICE", "OFFER_DISCOUNT")
                .contains(priceUse)) {
            throw invalid(rowIndex, "discountType no es valido: " + product.discountType());
        }
        if ("OFFER_PRICE".equals(priceUse) && blank(product.offerPrice())) {
            throw invalid(rowIndex, "offerPrice es obligatorio al usar precio de oferta");
        }
        if ("OFFER_DISCOUNT".equals(priceUse) && blank(product.offerDiscountPercent())) {
            throw invalid(rowIndex, "offerDiscountPercent es obligatorio al usar descuento de oferta");
        }
        if (("OFFER_PRICE".equals(priceUse) || "OFFER_DISCOUNT".equals(priceUse))
                && blank(product.offerFrom())) {
            throw invalid(rowIndex, "offerFrom es obligatorio para una oferta");
        }
        LocalDate from = parsedDate(product.offerFrom());
        LocalDate until = parsedDate(product.offerUntil());
        if (from != null && until != null && until.isBefore(from)) {
            throw invalid(rowIndex, "offerUntil no puede ser anterior a offerFrom");
        }
    }

    private static void validateSupplier(SupplierData supplier, int rowIndex, String field) {
        if (supplier == null) {
            return;
        }
        if (supplier.id() == null) {
            throw invalid(rowIndex, field + ".id es obligatorio");
        }
        nonNegative(supplier.grossPurchasePrice(), rowIndex, field + ".grossPurchasePrice", false);
        percentage(supplier.purchaseDiscount(), rowIndex, field + ".purchaseDiscount");
        nonNegative(supplier.netPurchasePrice(), rowIndex, field + ".netPurchasePrice", false);
    }

    private static void required(String value, int rowIndex, String field) {
        if (blank(value)) {
            throw invalid(rowIndex, field + " es obligatorio");
        }
    }

    private static void uuid(String value, int rowIndex, String field, boolean required) {
        if (blank(value)) {
            if (required) {
                throw invalid(rowIndex, field + " es obligatorio");
            }
            return;
        }
        try {
            UUID.fromString(value.trim());
        } catch (IllegalArgumentException exception) {
            throw invalid(rowIndex, field + " no es un UUID valido");
        }
    }

    private static <E extends Enum<E>> void enumValue(
            String value, Class<E> enumType, int rowIndex, String field) {
        required(value, rowIndex, field);
        try {
            Enum.valueOf(enumType, value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw invalid(rowIndex, field + " no es valido: " + value);
        }
    }

    private static void nonNegative(
            String value, int rowIndex, String field, boolean required) {
        BigDecimal number = decimal(value, rowIndex, field, required);
        if (number != null && number.signum() < 0) {
            throw invalid(rowIndex, field + " no puede ser negativo");
        }
    }

    private static void percentage(String value, int rowIndex, String field) {
        BigDecimal number = decimal(value, rowIndex, field, false);
        if (number != null && (number.signum() < 0 || number.compareTo(ONE_HUNDRED) > 0)) {
            throw invalid(rowIndex, field + " debe estar entre 0 y 100");
        }
    }

    private static BigDecimal decimal(
            String value, int rowIndex, String field, boolean required) {
        if (blank(value)) {
            if (required) {
                throw invalid(rowIndex, field + " es obligatorio");
            }
            return null;
        }
        try {
            return new BigDecimal(value.trim().replace(',', '.'));
        } catch (NumberFormatException exception) {
            throw invalid(rowIndex, field + " no es un numero valido");
        }
    }

    private static void date(String value, int rowIndex, String field) {
        if (blank(value)) {
            return;
        }
        try {
            LocalDate.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw invalid(rowIndex, field + " debe usar el formato yyyy-MM-dd");
        }
    }

    private static LocalDate parsedDate(String value) {
        return blank(value) ? null : LocalDate.parse(value.trim());
    }

    private static long estimatedSize(Row row) {
        long size = 256 + textSize(row.id(), row.query())
                + estimatedSize(row.product()) + estimatedSize(row.draft());
        for (SupplierData supplier : row.suppliers()) {
            size += estimatedSize(supplier);
        }
        return size + estimatedSize(row.pendingSupplier());
    }

    private static long estimatedSize(ProductData product) {
        if (product == null) {
            return 4;
        }
        return 384 + textSize(
                product.imageId(), product.warehouseId(), product.code(), product.barcode(),
                product.barcode2(), product.name(), product.description(), product.comments(),
                product.purchasePrice(), product.purchaseDiscountPercent(), product.salePrice(),
                product.memberPrice(), product.wholesalePrice(), product.offerPrice(),
                product.offerDiscountPercent(), product.productType(), product.discountType(),
                product.backendDiscountType(), product.familyId(), product.familyName(),
                product.subfamilyId(), product.subfamilyName(), product.taxId(), product.taxName(),
                product.taxesIncluded(), product.offerActive(), product.offerFrom(),
                product.offerUntil(), product.warehouseName(), product.quantity(),
                product.totalQuantity());
    }

    private static long estimatedSize(SupplierData supplier) {
        if (supplier == null) {
            return 4;
        }
        return 256 + textSize(
                supplier.supplierCode(), supplier.legalName(), supplier.tradeName(),
                supplier.documentNumber(), supplier.supplierReference(),
                supplier.grossPurchasePrice(), supplier.purchaseDiscount(),
                supplier.netPurchasePrice());
    }

    private static long textSize(String... values) {
        long size = 0;
        for (String value : values) {
            size += value == null ? 4 : value.length() + 2L;
        }
        return size;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank() || "-".equals(value.trim());
    }

    private static String normalized(String value) {
        return blank(value) ? null : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static IllegalArgumentException invalid(int rowIndex, String detail) {
        return new IllegalArgumentException("content[" + rowIndex + "]: " + detail);
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Row(
            @NotBlank @Size(max = 160) String id,
            boolean selected,
            @Size(max = 512) String query,
            @Valid ProductData product,
            @NotNull @Valid ProductData draft,
            List<@Valid SupplierData> suppliers,
            @Valid SupplierData pendingSupplier) {

        public Row {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("id de fila es obligatorio");
            }
            id = id.trim();
            query = query == null ? "" : query.trim();
            draft = draft == null ? ProductData.empty() : draft;
            suppliers = suppliers == null ? List.of() : List.copyOf(suppliers);
        }

        public ProductData effectiveProduct() {
            return product == null ? null : product.overlay(draft);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ProductData(
            UUID productId,
            Long version,
            String imageId,
            String warehouseId,
            String code,
            String barcode,
            String barcode2,
            String name,
            String description,
            String comments,
            String purchasePrice,
            String purchaseDiscountPercent,
            String salePrice,
            String memberPrice,
            String wholesalePrice,
            String offerPrice,
            String offerDiscountPercent,
            String productType,
            String discountType,
            String backendDiscountType,
            String familyId,
            String familyName,
            String subfamilyId,
            String subfamilyName,
            String taxId,
            String taxName,
            String taxesIncluded,
            String offerActive,
            String offerFrom,
            String offerUntil,
            String warehouseName,
            String quantity,
            String totalQuantity) {

        public static ProductData empty() {
            return new ProductData(
                    null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null);
        }

        public ProductData overlay(ProductData changes) {
            if (changes == null) {
                return this;
            }
            return new ProductData(
                    pick(changes.productId, productId),
                    pick(changes.version, version),
                    pick(changes.imageId, imageId),
                    pick(changes.warehouseId, warehouseId),
                    pick(changes.code, code),
                    pick(changes.barcode, barcode),
                    pick(changes.barcode2, barcode2),
                    pick(changes.name, name),
                    pick(changes.description, description),
                    pick(changes.comments, comments),
                    pick(changes.purchasePrice, purchasePrice),
                    pick(changes.purchaseDiscountPercent, purchaseDiscountPercent),
                    pick(changes.salePrice, salePrice),
                    pick(changes.memberPrice, memberPrice),
                    pick(changes.wholesalePrice, wholesalePrice),
                    pick(changes.offerPrice, offerPrice),
                    pick(changes.offerDiscountPercent, offerDiscountPercent),
                    pick(changes.productType, productType),
                    pick(changes.discountType, discountType),
                    pick(changes.backendDiscountType, backendDiscountType),
                    pick(changes.familyId, familyId),
                    pick(changes.familyName, familyName),
                    pick(changes.subfamilyId, subfamilyId),
                    pick(changes.subfamilyName, subfamilyName),
                    pick(changes.taxId, taxId),
                    pick(changes.taxName, taxName),
                    pick(changes.taxesIncluded, taxesIncluded),
                    pick(changes.offerActive, offerActive),
                    pick(changes.offerFrom, offerFrom),
                    pick(changes.offerUntil, offerUntil),
                    pick(changes.warehouseName, warehouseName),
                    pick(changes.quantity, quantity),
                    pick(changes.totalQuantity, totalQuantity));
        }

        public ProductData withPersistenceState(long actualVersion, UUID actualImageId) {
            return copyWithPersistenceState(
                    actualVersion,
                    actualImageId == null ? null : actualImageId.toString());
        }

        public ProductData withoutPersistenceState() {
            return copyWithPersistenceState(null, null);
        }

        private ProductData copyWithPersistenceState(Long actualVersion, String actualImageId) {
            return new ProductData(
                    productId,
                    actualVersion,
                    actualImageId,
                    warehouseId,
                    code,
                    barcode,
                    barcode2,
                    name,
                    description,
                    comments,
                    purchasePrice,
                    purchaseDiscountPercent,
                    salePrice,
                    memberPrice,
                    wholesalePrice,
                    offerPrice,
                    offerDiscountPercent,
                    productType,
                    discountType,
                    backendDiscountType,
                    familyId,
                    familyName,
                    subfamilyId,
                    subfamilyName,
                    taxId,
                    taxName,
                    taxesIncluded,
                    offerActive,
                    offerFrom,
                    offerUntil,
                    warehouseName,
                    quantity,
                    totalQuantity);
        }

        private static <T> T pick(T preferred, T fallback) {
            return preferred == null ? fallback : preferred;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SupplierData(
            @NotNull UUID id,
            String supplierCode,
            String legalName,
            String tradeName,
            String documentNumber,
            boolean active,
            String supplierReference,
            Boolean principal,
            Boolean lastSupplier,
            String grossPurchasePrice,
            String purchaseDiscount,
            String netPurchasePrice,
            Instant lastEntryAt) {
    }
}
