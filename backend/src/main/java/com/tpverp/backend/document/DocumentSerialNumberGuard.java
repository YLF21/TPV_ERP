package com.tpverp.backend.document;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.ProductType;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Serializes independent stock-output confirmations by store, product and normalized S/N.
 * PostgreSQL transaction advisory locks are deliberately acquired in lexical order
 * so confirmations containing several serials cannot deadlock each other.
 */
final class DocumentSerialNumberGuard {

    private static final EnumSet<CommercialDocumentType> GUARDED_TYPES = EnumSet.of(
            CommercialDocumentType.TICKET,
            CommercialDocumentType.ALBARAN_VENTA,
            CommercialDocumentType.FACTURA_VENTA);

    private final CommercialDocumentRepository documents;
    private final ProductRepository products;

    DocumentSerialNumberGuard(CommercialDocumentRepository documents) {
        this(documents, null);
    }

    DocumentSerialNumberGuard(CommercialDocumentRepository documents, ProductRepository products) {
        this.documents = Objects.requireNonNull(documents, "documents");
        this.products = products;
    }

    void lockAndValidate(CommercialDocument document, boolean appliesStock) {
        Objects.requireNonNull(document, "document");
        if (!appliesStock || !GUARDED_TYPES.contains(document.getTipo())) {
            return;
        }
        var productLines = validateStructure(document, appliesStock);
        if (productLines.isEmpty()) return;
        var normalized = normalizedSerials(productLines);
        if (normalized.isEmpty()) return;
        var ordered = orderedSerials(normalized);
        ordered.forEach(serial -> documents.lockSerialNumber(
                lockKey(document, serial.productId(), serial.serial())));
        if (hasUsedSerialNumbers(document, ordered)) {
            throw new IllegalArgumentException("message.document.serial_number_already_used");
        }
    }

    /** Validates the policy captured before an approved card/session payment.
     * Once the payment is approved, catalogue mutations must not prevent the
     * immutable fiscal snapshot from becoming a ticket. */
    void lockAndValidateSnapshot(CommercialDocument document, boolean appliesStock,
            List<DocumentLineCommand> snapshotLines) {
        lockAndValidateSnapshot(document, appliesStock, snapshotLines, false);
    }

    void lockAndValidateSnapshot(CommercialDocument document, boolean appliesStock,
            List<DocumentLineCommand> snapshotLines, boolean allowAlreadyUsed) {
        Objects.requireNonNull(snapshotLines, "snapshotLines");
        if (!appliesStock || !GUARDED_TYPES.contains(document.getTipo())) return;
        var productLines = validateSnapshotStructure(document, snapshotLines);
        var normalized = normalizedSerials(productLines);
        if (normalized.isEmpty()) return;
        var ordered = orderedSerials(normalized);
        ordered.forEach(serial -> documents.lockSerialNumber(
                lockKey(document, serial.productId(), serial.serial())));
        if (!allowAlreadyUsed && hasUsedSerialNumbers(document, ordered))
            throw new IllegalArgumentException("message.document.serial_number_already_used");
    }

    List<DocumentLine> validateSnapshot(CommercialDocument document, boolean appliesStock,
            List<DocumentLineCommand> snapshotLines) {
        Objects.requireNonNull(snapshotLines, "snapshotLines");
        if (!appliesStock || !GUARDED_TYPES.contains(document.getTipo())) return List.of();
        var productLines = validateSnapshotStructure(document, snapshotLines);
        var normalized = normalizedSerials(productLines);
        if (normalized.isEmpty()) return productLines;
        if (hasUsedSerialNumbers(document, orderedSerials(normalized))) {
            throw new IllegalArgumentException("message.document.serial_number_already_used");
        }
        return productLines;
    }

    private List<DocumentLine> validateSnapshotStructure(CommercialDocument document,
            List<DocumentLineCommand> snapshotLines) {
        var lines = document.getLineas();
        var frozenByProduct = snapshotLines.stream()
                .filter(command -> command.productoId() != null
                        && (command.lineType() == null || command.lineType() == DocumentLineType.PRODUCT)
                        && command.cantidad().signum() > 0)
                .collect(java.util.stream.Collectors.toMap(
                        DocumentLineCommand::productoId,
                        command -> Boolean.TRUE.equals(command.requiresSerialNumber()),
                        (first, ignored) -> first));
        var productLines = new java.util.ArrayList<DocumentLine>();
        for (int i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
            if (line.getLineType() != DocumentLineType.PRODUCT || line.getCantidad().signum() <= 0)
                continue;
            if (!frozenByProduct.containsKey(line.getProductoId()))
                throw new IllegalArgumentException("message.document.serial_number_required");
            if (Boolean.TRUE.equals(frozenByProduct.get(line.getProductoId()))) {
                var serials = line.getSerialNumbers();
                if (serials == null || line.getCantidad().stripTrailingZeros().scale() > 0
                        || line.getCantidad().intValueExact() != serials.size()
                        || serials.stream().anyMatch(value -> value == null || value.isBlank()))
                    throw new IllegalArgumentException("message.document.serial_number_required");
            }
            productLines.add(line);
        }
        var normalized = normalizedSerials(productLines);
        if (normalized.stream().distinct().count() != normalized.size())
            throw new IllegalArgumentException("message.document.serial_number_duplicated");
        return productLines;
    }

    List<DocumentLine> validate(CommercialDocument document, boolean appliesStock) {
        var productLines = validateStructure(document, appliesStock);
        if (productLines.isEmpty()) return productLines;
        var normalized = normalizedSerials(productLines);
        if (normalized.isEmpty()) return productLines;
        if (hasUsedSerialNumbers(document, orderedSerials(normalized))) {
            throw new IllegalArgumentException("message.document.serial_number_already_used");
        }
        return productLines;
    }

    private List<DocumentLine> validateStructure(CommercialDocument document, boolean appliesStock) {
        Objects.requireNonNull(document, "document");
        if (!appliesStock || !GUARDED_TYPES.contains(document.getTipo())) return List.of();
        var productLines = document.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.PRODUCT)
                .filter(line -> line.getCantidad().signum() > 0)
                .toList();
        if (products != null) {
            var ids = productLines.stream().map(DocumentLine::getProductoId).filter(Objects::nonNull).distinct().toList();
            var catalog = products.findAllByStoreIdAndIdIn(document.getTiendaId(), ids).stream()
                    .collect(java.util.stream.Collectors.toMap(Product::getId, value -> value));
            for (var line : productLines) {
                var product = catalog.get(line.getProductoId());
                if (product != null && product.isRequiresSerialNumber()) {
                    var serials = line.getSerialNumbers();
                    if (product.getProductType() != ProductType.UNIT
                            || line.getCantidad().stripTrailingZeros().scale() > 0
                            || line.getCantidad().intValueExact() != serials.size()
                            || serials.stream().anyMatch(value -> value == null || value.isBlank())) {
                        throw new IllegalArgumentException("message.document.serial_number_required");
                    }
                }
            }
        }
        var normalized = normalizedSerials(productLines);
        if (normalized.isEmpty()) return productLines;
        if (normalized.stream().distinct().count() != normalized.size()) {
            throw new IllegalArgumentException("message.document.serial_number_duplicated");
        }
        return productLines;
    }

    private static List<ProductSerial> normalizedSerials(List<DocumentLine> productLines) {
        return productLines.stream()
                .flatMap(line -> line.getSerialNumbers().stream()
                        .map(value -> new ProductSerial(line.getProductoId(), normalize(value))))
                .filter(value -> !value.serial().isBlank())
                .toList();
    }

    private static List<ProductSerial> orderedSerials(List<ProductSerial> serials) {
        return serials.stream().distinct().sorted(java.util.Comparator
                .comparing((ProductSerial value) -> value.productId().toString())
                .thenComparing(ProductSerial::serial)).toList();
    }

    private boolean hasUsedSerialNumbers(CommercialDocument document, List<ProductSerial> serials) {
        var byProduct = serials.stream().collect(java.util.stream.Collectors.groupingBy(
                ProductSerial::productId, java.util.LinkedHashMap::new,
                java.util.stream.Collectors.mapping(ProductSerial::serial,
                        java.util.stream.Collectors.toList())));
        return byProduct.entrySet().stream().anyMatch(entry -> !documents.usedSerialNumbers(
                document.getTiendaId(), entry.getKey(), entry.getValue()).isEmpty());
    }

    private record ProductSerial(java.util.UUID productId, String serial) { }

    static String lockKey(CommercialDocument document, java.util.UUID productId, String normalizedSerial) {
        return "document-sale-serial|" + document.getTiendaId() + "|" + productId
                + "|" + normalizedSerial.length() + ":" + normalizedSerial;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
