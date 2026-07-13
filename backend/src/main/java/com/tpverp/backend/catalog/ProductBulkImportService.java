package com.tpverp.backend.catalog;

import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentRepository;
import com.tpverp.backend.document.DocumentLine;
import com.tpverp.backend.document.DocumentLineType;
import com.tpverp.backend.document.DocumentStatus;
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

    private final CommercialDocumentRepository documents;
    private final ProductRepository products;
    private final SupplierRepository suppliers;
    private final CurrentOrganization organization;

    public ProductBulkImportService(
            CommercialDocumentRepository documents,
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
        List<CommercialDocument> invoices = documents.findPurchaseInvoicesForBulkEdit(storeId);
        return purchaseDocuments(storeId, invoices);
    }

    @Transactional(readOnly = true)
    public List<PurchaseDocumentOptionView> purchaseDeliveryNotes() {
        UUID storeId = organization.currentStore().getId();
        List<CommercialDocument> deliveryNotes = documents.findPurchaseDeliveryNotesForBulkEdit(storeId);
        return purchaseDocuments(storeId, deliveryNotes);
    }

    private List<PurchaseDocumentOptionView> purchaseDocuments(
            UUID storeId, List<CommercialDocument> purchaseDocuments) {
        Set<UUID> importableProductIds = importableProductIds(storeId, purchaseDocuments);
        Map<UUID, Supplier> supplierIndex = suppliers.findAllById(
                        purchaseDocuments.stream()
                                .map(CommercialDocument::getProveedorId)
                                .filter(java.util.Objects::nonNull)
                                .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(Supplier::getId, Function.identity()));
        return purchaseDocuments.stream()
                .map(document -> PurchaseDocumentOptionView.from(
                        document, supplierIndex.get(document.getProveedorId()), importableProductIds))
                .filter(document -> document.productCount() > 0)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PurchaseDocumentProductView> purchaseInvoiceProducts(UUID invoiceId) {
        UUID storeId = organization.currentStore().getId();
        CommercialDocument invoice = documents.findPurchaseInvoiceForBulkEdit(
                        storeId, invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Factura de compra no encontrada"));
        return purchaseDocumentProducts(storeId, invoice);
    }

    @Transactional(readOnly = true)
    public List<PurchaseDocumentProductView> purchaseDeliveryNoteProducts(UUID deliveryNoteId) {
        UUID storeId = organization.currentStore().getId();
        CommercialDocument deliveryNote = documents.findPurchaseDeliveryNoteForBulkEdit(
                        storeId, deliveryNoteId)
                .orElseThrow(() -> new IllegalArgumentException("Albaran de compra no encontrado"));
        return purchaseDocumentProducts(storeId, deliveryNote);
    }

    private List<PurchaseDocumentProductView> purchaseDocumentProducts(
            UUID storeId, CommercialDocument purchaseDocument) {
        Set<UUID> importableProductIds = importableProductIds(storeId, List.of(purchaseDocument));
        Map<UUID, DocumentLine> lastLineByProduct = new LinkedHashMap<>();
        purchaseDocument.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.PRODUCT)
                .filter(line -> importableProductIds.contains(line.getProductoId()))
                .forEach(line -> lastLineByProduct.put(line.getProductoId(), line));
        return lastLineByProduct.values().stream()
                .map(PurchaseDocumentProductView::from)
                .toList();
    }

    private Set<UUID> importableProductIds(UUID storeId, List<CommercialDocument> invoices) {
        Set<UUID> referencedProductIds = invoices.stream()
                .flatMap(invoice -> invoice.getLineas().stream())
                .filter(line -> line.getLineType() == DocumentLineType.PRODUCT)
                .map(DocumentLine::getProductoId)
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
            DocumentStatus status,
            UUID supplierId,
            String supplierName,
            BigDecimal total,
            int productCount) {

        static PurchaseDocumentOptionView from(
                CommercialDocument document, Supplier supplier, Set<UUID> importableProductIds) {
            long productCount = document.getLineas().stream()
                    .filter(line -> line.getLineType() == DocumentLineType.PRODUCT)
                    .map(DocumentLine::getProductoId)
                    .filter(importableProductIds::contains)
                    .distinct()
                    .count();
            return new PurchaseDocumentOptionView(
                    document.getId(),
                    document.getNumero(),
                    document.getFecha(),
                    document.getEstado(),
                    document.getProveedorId(),
                    supplier == null ? null : supplier.getLegalName(),
                    document.getTotal(),
                    Math.toIntExact(productCount));
        }
    }

    public record PurchaseDocumentProductView(
            UUID productId,
            BigDecimal grossPurchasePrice,
            BigDecimal purchaseDiscount) {

        static PurchaseDocumentProductView from(DocumentLine line) {
            return new PurchaseDocumentProductView(
                    line.getProductoId(), line.getPrecioUnitario(), line.getDescuento());
        }
    }
}
