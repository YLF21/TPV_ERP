package com.tpverp.backend.excel;

import com.tpverp.backend.catalog.Warehouse;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.inventory.WarehouseExcelImportMetadata;
import com.tpverp.backend.inventory.WarehouseExcelImportProvenanceService;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.Supplier;
import com.tpverp.backend.party.SupplierRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Single transaction boundary for catalog writes and Warehouse provenance signing. */
@Service
public class ProductExcelImportApplyCommitter {
    private final ProductExcelImportApplyWriter writer;
    private final CurrentOrganization organization;
    private final WarehouseRepository warehouses;
    private final SupplierRepository suppliers;
    private final WarehouseExcelImportProvenanceService provenance;

    /** Compatibility adapter used only by direct unit-test construction. */
    public ProductExcelImportApplyCommitter(ProductExcelImportApplyWriter writer) {
        this(writer, null, null, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProductExcelImportApplyCommitter(ProductExcelImportApplyWriter writer,
            CurrentOrganization organization, WarehouseRepository warehouses,
            SupplierRepository suppliers, WarehouseExcelImportProvenanceService provenance) {
        this.writer = writer;
        this.organization = organization;
        this.warehouses = warehouses;
        this.suppliers = suppliers;
        this.provenance = provenance;
    }

    @Transactional
    public CommitResult commit(ProductExcelImportPreviewService.PreviewResult preview,
            List<ProductExcelImportApplyService.WriteItem> writes,
            ProductExcelImportApplyService.ApplyRequest request) {
        if (organization == null) {
            return new CommitResult(writer.write(writes), null, null);
        }
        var store = organization.currentStore();
        var company = organization.currentCompany();
        String context = request.preview() == null || request.preview().options() == null
                ? null : request.preview().options().context();
        boolean warehouseContext = "WAREHOUSE_INPUT".equals(context);
        if (warehouseContext) validateWarehouse(request, store.getId(), company.getId());

        var applied = writer.write(writes);
        WarehouseExcelImportMetadata metadata = null;
        String token = null;
        if (warehouseContext) {
            if (request.warehouseId() == null || request.documentDate() == null) {
                throw new WarehouseImportContextException("warehouseId y documentDate son obligatorios");
            }
            UUID supplierId = Boolean.TRUE.equals(request.updateSupplier()) ? request.supplierId() : null;
            metadata = ProductExcelImportApplyService.warehouseMetadata(preview, applied, request);
            token = provenance.signApply(company.getId(), store.getId(), request.warehouseId(),
                    request.documentDate(), supplierId, metadata);
        }
        return new CommitResult(applied, metadata, token);
    }

    private void validateWarehouse(ProductExcelImportApplyService.ApplyRequest request,
            UUID storeId, UUID companyId) {
        if (request.warehouseId() == null || request.documentDate() == null) {
            throw new WarehouseImportContextException("warehouseId y documentDate son obligatorios");
        }
        Warehouse warehouse = warehouses.findByIdAndStoreIdForUpdate(request.warehouseId(), storeId)
                .orElseThrow(() -> new WarehouseImportContextException("El almacen no pertenece a la tienda activa"));
        if (!warehouse.isActive()) throw new WarehouseImportContextException("El almacen esta inactivo");
        if (Boolean.TRUE.equals(request.updateSupplier())) {
            if (request.supplierId() == null) throw new WarehouseImportContextException("supplierId es obligatorio");
            Supplier supplier = suppliers.findByIdAndCompanyIdForUpdate(request.supplierId(), companyId)
                    .orElseThrow(() -> new WarehouseImportContextException("El proveedor no pertenece a la empresa activa"));
            if (!supplier.isActive()) throw new WarehouseImportContextException("El proveedor esta inactivo");
        }
    }

    public record CommitResult(List<ProductExcelImportApplyWriter.AppliedProduct> applied,
            WarehouseExcelImportMetadata warehouseMetadata, String warehouseProvenanceToken) { }

    public static final class WarehouseImportContextException extends IllegalArgumentException {
        public WarehouseImportContextException(String message) { super(message); }
    }
}
