package com.tpverp.backend.document;

import com.tpverp.backend.catalog.Warehouse;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.Supplier;
import com.tpverp.backend.party.SupplierRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PurchaseDocumentQueryService {

    private final CommercialDocumentRepository documents;
    private final SupplierRepository suppliers;
    private final WarehouseRepository warehouses;
    private final CurrentOrganization organization;

    public PurchaseDocumentQueryService(
            CommercialDocumentRepository documents,
            SupplierRepository suppliers,
            WarehouseRepository warehouses,
            CurrentOrganization organization) {
        this.documents = documents;
        this.suppliers = suppliers;
        this.warehouses = warehouses;
        this.organization = organization;
    }

    @Transactional(readOnly = true)
    public List<PurchaseDocumentView> list(CommercialDocumentType type) {
        requirePurchaseType(type);
        var storeId = organization.currentStore().getId();
        var purchaseDocuments = documents.findByTiendaIdAndTipoOrderByFechaDescNumeroDesc(
                storeId, type);
        var supplierIndex = suppliers.findAllById(purchaseDocuments.stream()
                        .map(CommercialDocument::getProveedorId)
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(Supplier::getId, Function.identity()));
        Map<UUID, Warehouse> warehouseIndex = warehouses.findAllById(purchaseDocuments.stream()
                        .map(CommercialDocument::getAlmacenId)
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toSet()))
                .stream()
                .filter(warehouse -> warehouse.getStoreId().equals(storeId))
                .collect(Collectors.toMap(Warehouse::getId, Function.identity()));
        return purchaseDocuments.stream()
                .map(document -> PurchaseDocumentView.from(
                        document,
                        supplierIndex.get(document.getProveedorId()),
                        warehouseIndex.get(document.getAlmacenId())))
                .toList();
    }

    private static void requirePurchaseType(CommercialDocumentType type) {
        if (type != CommercialDocumentType.ALBARAN_COMPRA
                && type != CommercialDocumentType.FACTURA_COMPRA) {
            throw new IllegalArgumentException("Tipo de documento de compra no válido");
        }
    }

    public record PurchaseDocumentView(
            UUID id,
            CommercialDocumentType type,
            DocumentStatus status,
            String number,
            String externalNumber,
            LocalDate date,
            UUID supplierId,
            String supplierName,
            UUID warehouseId,
            String warehouseName,
            BigDecimal base,
            BigDecimal tax,
            BigDecimal total,
            BigDecimal paid,
            BigDecimal pending,
            List<PurchaseDocumentLineView> lines) {

        static PurchaseDocumentView from(
                CommercialDocument document,
                Supplier supplier,
                Warehouse warehouse) {
            var lines = document.getLineas().stream()
                    .sorted(Comparator.comparingInt(DocumentLine::getPosicion))
                    .map(PurchaseDocumentLineView::from)
                    .toList();
            return new PurchaseDocumentView(
                    document.getId(),
                    document.getTipo(),
                    document.getEstado(),
                    document.getNumero(),
                    document.getNumeroExterno(),
                    document.getFecha(),
                    document.getProveedorId(),
                    supplier == null ? null : supplier.getLegalName(),
                    document.getAlmacenId(),
                    warehouse == null ? null : warehouse.getName(),
                    document.getBaseTotal(),
                    document.getImpuestoTotal(),
                    document.getTotal(),
                    document.getPaidTotal(),
                    document.getPendingTotal(),
                    lines);
        }
    }

    public record PurchaseDocumentLineView(
            UUID id,
            UUID productId,
            DocumentLineType lineType,
            int position,
            String code,
            String name,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal discount,
            String taxRegime,
            BigDecimal taxPercentage,
            BigDecimal base,
            BigDecimal tax,
            BigDecimal total) {

        static PurchaseDocumentLineView from(DocumentLine line) {
            return new PurchaseDocumentLineView(
                    line.getId(),
                    line.getProductoId(),
                    line.getLineType(),
                    line.getPosicion(),
                    line.getCodigo(),
                    line.getNombre(),
                    line.getCantidad(),
                    line.getPrecioUnitario(),
                    line.getDescuento(),
                    line.getRegimenImpuesto(),
                    line.getPorcentajeImpuesto(),
                    line.getBase(),
                    line.getImpuesto(),
                    line.getTotal());
        }
    }
}
