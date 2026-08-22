package com.tpverp.backend.catalog;

import com.tpverp.backend.inventory.WarehouseInput;
import com.tpverp.backend.inventory.WarehouseInputDocumentType;
import com.tpverp.backend.inventory.WarehouseInputLine;
import com.tpverp.backend.inventory.WarehouseInputRepository;
import com.tpverp.backend.inventory.WarehouseInputStatus;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.Supplier;
import com.tpverp.backend.party.SupplierRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductBulkImportService {

    private final WarehouseInputRepository documents;
    private final ProductRepository products;
    private final SupplierRepository suppliers;
    private final CurrentOrganization organization;

    public ProductBulkImportService(
            WarehouseInputRepository documents,
            ProductRepository products,
            SupplierRepository suppliers,
            CurrentOrganization organization) {
        this.documents = documents;
        this.products = products;
        this.suppliers = suppliers;
        this.organization = organization;
    }

    @Transactional(readOnly = true)
    public List<PurchaseDocumentOptionView> purchaseInvoices() {
        UUID storeId = organization.currentStore().getId();
        List<WarehouseInput> invoices = documents.findByStoreIdOrderByFechaDesc(storeId).stream()
                .filter(input -> input.getDocumentType() == WarehouseInputDocumentType.FACTURA_ENTRADA)
                .filter(input -> input.getStatus() == WarehouseInputStatus.CONFIRMADA)
                .toList();
        return purchaseDocuments(storeId, invoices);
    }

    @Transactional(readOnly = true)
    public List<PurchaseDocumentOptionView> purchaseDeliveryNotes() {
        UUID storeId = organization.currentStore().getId();
        List<WarehouseInput> deliveryNotes = documents.findByStoreIdOrderByFechaDesc(storeId).stream()
                .filter(input -> input.getDocumentType() == WarehouseInputDocumentType.ALBARAN_ENTRADA)
                .filter(input -> input.getStatus() == WarehouseInputStatus.CONFIRMADA)
                .toList();
        return purchaseDocuments(storeId, deliveryNotes);
    }

    private List<PurchaseDocumentOptionView> purchaseDocuments(
            UUID storeId, List<WarehouseInput> purchaseDocuments) {
        Set<UUID> importableProductIds = importableProductIds(storeId, purchaseDocuments);
        Map<UUID, Supplier> supplierIndex = suppliers.findAllById(
                        purchaseDocuments.stream()
                                .map(WarehouseInput::getSupplierId)
                                .filter(java.util.Objects::nonNull)
                                .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(Supplier::getId, Function.identity()));
        return purchaseDocuments.stream()
                .map(document -> PurchaseDocumentOptionView.from(
                        document, supplierIndex.get(document.getSupplierId()), importableProductIds))
                .filter(document -> document.productCount() > 0)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PurchaseDocumentProductView> purchaseInvoiceProducts(UUID invoiceId) {
        UUID storeId = organization.currentStore().getId();
        WarehouseInput invoice = documents.findByIdAndStoreId(invoiceId, storeId)
                .filter(input -> input.getDocumentType() == WarehouseInputDocumentType.FACTURA_ENTRADA)
                .filter(input -> input.getStatus() == WarehouseInputStatus.CONFIRMADA)
                .orElseThrow(() -> new IllegalArgumentException("Factura de compra no encontrada"));
        return purchaseDocumentProducts(storeId, invoice);
    }

    @Transactional(readOnly = true)
    public List<PurchaseDocumentProductView> purchaseDeliveryNoteProducts(UUID deliveryNoteId) {
        UUID storeId = organization.currentStore().getId();
        WarehouseInput deliveryNote = documents.findByIdAndStoreId(deliveryNoteId, storeId)
                .filter(input -> input.getDocumentType() == WarehouseInputDocumentType.ALBARAN_ENTRADA)
                .filter(input -> input.getStatus() == WarehouseInputStatus.CONFIRMADA)
                .orElseThrow(() -> new IllegalArgumentException("Albaran de compra no encontrado"));
        return purchaseDocumentProducts(storeId, deliveryNote);
    }

    private List<PurchaseDocumentProductView> purchaseDocumentProducts(
            UUID storeId, WarehouseInput purchaseDocument) {
        Set<UUID> importableProductIds = importableProductIds(storeId, List.of(purchaseDocument));
        Map<UUID, WarehouseInputLine> lastLineByProduct = new LinkedHashMap<>();
        purchaseDocument.getLines().stream()
                .filter(line -> importableProductIds.contains(line.getProductId()))
                .forEach(line -> lastLineByProduct.put(line.getProductId(), line));
        return lastLineByProduct.values().stream()
                .map(PurchaseDocumentProductView::from)
                .toList();
    }

    private Set<UUID> importableProductIds(UUID storeId, List<WarehouseInput> invoices) {
        Set<UUID> referencedProductIds = invoices.stream()
                .flatMap(invoice -> invoice.getLines().stream())
                .map(WarehouseInputLine::getProductId)
                .collect(Collectors.toSet());
        if (referencedProductIds.isEmpty()) {
            return Set.of();
        }
        return products.findAllByStoreIdAndIdIn(storeId, referencedProductIds).stream()
                .map(Product::getId)
                .collect(Collectors.toSet());
    }

    public record PurchaseDocumentOptionView(
            UUID id,
            String number,
            LocalDate date,
            String status,
            UUID supplierId,
            String supplierName,
            BigDecimal total,
            int productCount) {

        static PurchaseDocumentOptionView from(
            WarehouseInput document, Supplier supplier, Set<UUID> importableProductIds) {
            long productCount = document.getLines().stream()
                    .map(WarehouseInputLine::getProductId)
                    .filter(importableProductIds::contains)
                    .distinct()
                    .count();
            return new PurchaseDocumentOptionView(
                    document.getId(),
                    document.getNumber(),
                    document.getDate(),
                    document.getStatus().name(),
                    document.getSupplierId(),
                    supplier == null ? null : supplier.getLegalName(),
                    document.getTotal(),
                    Math.toIntExact(productCount));
        }
    }

    public record PurchaseDocumentProductView(
            UUID productId,
            BigDecimal grossPurchasePrice,
            BigDecimal purchaseDiscount) {

        static PurchaseDocumentProductView from(WarehouseInputLine line) {
            return new PurchaseDocumentProductView(
                    line.getProductId(), line.getPurchaseUnitPrice(), line.getDiscount());
        }
    }
}
