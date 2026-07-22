package com.tpverp.backend.document;

import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.Customer;
import com.tpverp.backend.party.CustomerRepository;
import com.tpverp.backend.party.Supplier;
import com.tpverp.backend.party.SupplierRepository;
import com.tpverp.backend.shared.api.PagedResult;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentReportService {

    private static final int DEFAULT_LIMIT = 500;
    private static final int MAX_LIMIT = 500;
    private static final EnumSet<CommercialDocumentType> DELIVERY_NOTES = EnumSet.of(
            CommercialDocumentType.ALBARAN_VENTA, CommercialDocumentType.ALBARAN_COMPRA);
    private static final EnumSet<CommercialDocumentType> SALES_DELIVERY_NOTES = EnumSet.of(
            CommercialDocumentType.ALBARAN_VENTA);
    private static final EnumSet<CommercialDocumentType> INVOICES = EnumSet.of(
            CommercialDocumentType.FACTURA_VENTA, CommercialDocumentType.FACTURA_COMPRA,
            CommercialDocumentType.RECTIFICATIVA_VENTA, CommercialDocumentType.RECTIFICATIVA_COMPRA);
    private static final EnumSet<CommercialDocumentType> SALES_INVOICES = EnumSet.of(
            CommercialDocumentType.FACTURA_VENTA, CommercialDocumentType.RECTIFICATIVA_VENTA);
    private static final EnumSet<CommercialDocumentType> PURCHASE_DELIVERY_NOTES = EnumSet.of(
            CommercialDocumentType.ALBARAN_COMPRA);
    private static final EnumSet<CommercialDocumentType> PURCHASE_INVOICES = EnumSet.of(
            CommercialDocumentType.FACTURA_COMPRA, CommercialDocumentType.RECTIFICATIVA_COMPRA);

    private final CommercialDocumentRepository documents;
    private final CurrentOrganization organization;
    private final CustomerRepository customers;
    private final SupplierRepository suppliers;
    private final WarehouseRepository warehouses;
    private final DocumentAttributionResolver attributions;

    public DocumentReportService(
            CommercialDocumentRepository documents,
            CurrentOrganization organization,
            CustomerRepository customers,
            SupplierRepository suppliers,
            WarehouseRepository warehouses,
            DocumentAttributionResolver attributions) {
        this.documents = documents;
        this.organization = organization;
        this.customers = customers;
        this.suppliers = suppliers;
        this.warehouses = warehouses;
        this.attributions = attributions;
    }

    @Transactional(readOnly = true)
    public PagedResult<DocumentReportView> listInvoices(Integer limit, String cursor, boolean includePurchaseDocuments) {
        return listInvoices(limit, cursor, true, includePurchaseDocuments);
    }

    @Transactional(readOnly = true)
    public PagedResult<DocumentReportView> listInvoices(
            Integer limit,
            String cursor,
            boolean includeSalesDocuments,
            boolean includePurchaseDocuments) {
        return list(documentTypes(
                includeSalesDocuments, includePurchaseDocuments,
                SALES_INVOICES, PURCHASE_INVOICES), limit, cursor);
    }

    @Transactional(readOnly = true)
    public PagedResult<DocumentReportView> listDeliveryNotes(Integer limit, String cursor, boolean includePurchaseDocuments) {
        return listDeliveryNotes(limit, cursor, true, includePurchaseDocuments);
    }

    @Transactional(readOnly = true)
    public PagedResult<DocumentReportView> listDeliveryNotes(
            Integer limit,
            String cursor,
            boolean includeSalesDocuments,
            boolean includePurchaseDocuments) {
        return list(documentTypes(
                includeSalesDocuments, includePurchaseDocuments,
                SALES_DELIVERY_NOTES, PURCHASE_DELIVERY_NOTES), limit, cursor);
    }

    @Transactional(readOnly = true)
    public List<DocumentReportView> allInvoices(
            boolean includeSalesDocuments,
            boolean includePurchaseDocuments) {
        return all(documentTypes(
                includeSalesDocuments, includePurchaseDocuments,
                SALES_INVOICES, PURCHASE_INVOICES));
    }

    @Transactional(readOnly = true)
    public List<DocumentReportView> allDeliveryNotes(
            boolean includeSalesDocuments,
            boolean includePurchaseDocuments) {
        return all(documentTypes(
                includeSalesDocuments, includePurchaseDocuments,
                SALES_DELIVERY_NOTES, PURCHASE_DELIVERY_NOTES));
    }

    private List<DocumentReportView> all(Collection<CommercialDocumentType> types) {
        var result = new ArrayList<DocumentReportView>();
        String cursor = null;
        do {
            var page = list(types, MAX_LIMIT, cursor);
            result.addAll(page.items());
            cursor = page.hasMore() ? page.nextCursor() : null;
        } while (cursor != null);
        return result;
    }

    private PagedResult<DocumentReportView> list(
            Collection<CommercialDocumentType> types,
            Integer requestedLimit,
            String cursor) {
        var store = organization.currentStore();
        var limit = normalizedLimit(requestedLimit);
        var parsedCursor = parseCursor(cursor);
        var pageRequest = PageRequest.of(0, limit + 1);
        var values = parsedCursor.date() == null
                ? documents.findReportDocuments(store.getId(), types, pageRequest)
                : documents.findReportDocumentsAfter(
                        store.getId(), types, parsedCursor.date(), parsedCursor.id(), pageRequest);
        var hasMore = values.size() > limit;
        var pageValues = hasMore ? new ArrayList<>(values.subList(0, limit)) : values;
        var customerIndex = customers.findAllById(values.stream()
                        .map(CommercialDocument::getClienteId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet()))
                .stream()
                .filter(customer -> customer.getFiscalName() != null)
                .collect(Collectors.toMap(
                        Customer::getId,
                        customer -> new DocumentReportView.PartySummary(
                                customer.getClientId(), customer.getFiscalName())));
        var supplierIndex = suppliers.findAllById(values.stream()
                        .map(CommercialDocument::getProveedorId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(
                        Supplier::getId,
                        supplier -> new DocumentReportView.PartySummary(
                                supplier.getSupplierId(), supplier.getLegalName())));
        var warehouseIndex = warehouses.findAllById(values.stream()
                        .map(CommercialDocument::getAlmacenId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet()))
                .stream()
                .filter(warehouse -> store.getId().equals(warehouse.getStoreId()))
                .collect(Collectors.toMap(
                        com.tpverp.backend.catalog.Warehouse::getId,
                        com.tpverp.backend.catalog.Warehouse::getName));
        var attributionIndex = attributions.resolve(pageValues);

        var items = pageValues.stream()
                .map(document -> DocumentReportView.from(
                        document,
                        customerIndex.get(document.getClienteId()),
                        supplierIndex.get(document.getProveedorId()),
                        warehouseIndex.get(document.getAlmacenId()),
                        attributionIndex.get(document.getId())))
                .toList();
        return new PagedResult<>(items, hasMore ? cursorFor(pageValues.get(pageValues.size() - 1)) : null, hasMore);
    }

    private static EnumSet<CommercialDocumentType> documentTypes(
            boolean includeSalesDocuments,
            boolean includePurchaseDocuments,
            EnumSet<CommercialDocumentType> salesTypes,
            EnumSet<CommercialDocumentType> purchaseTypes) {
        var result = EnumSet.noneOf(CommercialDocumentType.class);
        if (includeSalesDocuments) {
            result.addAll(salesTypes);
        }
        if (includePurchaseDocuments) {
            result.addAll(purchaseTypes);
        }
        return result;
    }

    private static int normalizedLimit(Integer requestedLimit) {
        if (requestedLimit == null || requestedLimit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requestedLimit, MAX_LIMIT);
    }

    private static Cursor parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new Cursor(null, null);
        }
        var parts = cursor.split("\\|", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("cursor invalido");
        }
        return new Cursor(LocalDate.parse(parts[0]), UUID.fromString(parts[1]).toString());
    }

    private static String cursorFor(CommercialDocument document) {
        return document.getFecha() + "|" + document.getId();
    }

    private record Cursor(LocalDate date, String id) {
    }
}
