package com.tpverp.backend.document;

import com.tpverp.backend.catalog.Warehouse;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.inventory.WarehouseInputDocumentType;
import com.tpverp.backend.inventory.WarehouseInputService;
import com.tpverp.backend.inventory.OperationalWarehousePrintService;
import com.tpverp.backend.document.template.RenderedDocumentView;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.Supplier;
import com.tpverp.backend.party.SupplierRepository;
import com.tpverp.backend.security.application.PermissionChecks;
import com.tpverp.backend.shared.api.PagedResult;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WarehouseInputReportService {

    private final WarehouseInputService inputs;
    private final SupplierRepository suppliers;
    private final WarehouseRepository warehouses;
    private final CurrentOrganization organization;
    private final OperationalWarehousePrintService printing;

    public WarehouseInputReportService(WarehouseInputService inputs, SupplierRepository suppliers,
            WarehouseRepository warehouses, CurrentOrganization organization, OperationalWarehousePrintService printing) {
        this.inputs = inputs;
        this.suppliers = suppliers;
        this.warehouses = warehouses;
        this.organization = organization;
        this.printing = printing;
    }

    @Transactional(readOnly = true)
    public PagedResult<WarehouseInputReportView> listPage(WarehouseInputDocumentType type,
            Integer limit, String cursor, LocalDate dateFrom, LocalDate dateTo,
            Authentication authentication) {
        requireAccess(type, authentication);

        var page = inputs.listPage(limit, cursor, type, dateFrom, dateTo);
        var supplierIds = page.items().stream().map(value -> value.supplierId())
                .filter(Objects::nonNull).distinct().toList();
        var warehouseIds = page.items().stream().map(value -> value.warehouseId())
                .filter(Objects::nonNull).distinct().toList();
        Map<UUID, Supplier> supplierIndex = supplierIds.isEmpty() ? Map.of()
                : suppliers.findByCompanyIdAndIdIn(organization.currentCompany().getId(), supplierIds)
                        .stream().collect(Collectors.toMap(Supplier::getId, Function.identity()));
        Map<UUID, Warehouse> warehouseIndex = warehouseIds.isEmpty() ? Map.of()
                : warehouses.findByStoreIdAndIdIn(organization.currentStore().getId(), warehouseIds)
                        .stream().collect(Collectors.toMap(Warehouse::getId, Function.identity()));
        var items = page.items().stream().map(value -> {
            var supplier = value.supplierId() == null ? null : supplierIndex.get(value.supplierId());
            var warehouse = value.warehouseId() == null ? null : warehouseIndex.get(value.warehouseId());
            return new WarehouseInputReportView(value,
                    supplier == null ? null : supplier.getSupplierId(),
                    supplier == null ? null : supplier.getLegalName(),
                    warehouse == null ? null : warehouse.getName());
        }).toList();
        return new PagedResult<>(items, page.nextCursor(), page.hasMore());
    }

    @Transactional(readOnly = true)
    public RenderedDocumentView printDocument(UUID id, Authentication authentication) {
        if (!PermissionChecks.hasPurchaseDocumentRead(authentication)) {
            throw new AccessDeniedException("Sin permiso para consultar este informe de entradas");
        }
        // Resolve the actual stored type inside the current store, never a client-supplied type.
        var document = inputs.view(id);
        requireAccess(document.documentType(), authentication);
        return printing.input(id);
    }

    private static void requireAccess(WarehouseInputDocumentType type, Authentication authentication) {
        if (type == null) throw new IllegalArgumentException("El tipo de entrada es obligatorio");
        boolean allowed = type == WarehouseInputDocumentType.ENTRADA_ALMACEN
                ? PermissionChecks.hasWarehouseManagement(authentication)
                : PermissionChecks.hasPurchaseDocumentRead(authentication);
        if (!allowed) throw new AccessDeniedException("Sin permiso para consultar este informe de entradas");
    }
}
