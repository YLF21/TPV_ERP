package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tpverp.backend.catalog.Warehouse;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.inventory.WarehouseInputDocumentType;
import com.tpverp.backend.inventory.WarehouseInputService;
import com.tpverp.backend.inventory.WarehouseInputView;
import com.tpverp.backend.inventory.OperationalWarehousePrintService;
import com.tpverp.backend.document.template.RenderedDocumentView;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.party.Supplier;
import com.tpverp.backend.party.SupplierRepository;
import com.tpverp.backend.shared.api.PagedResult;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class WarehouseInputReportServiceTest {
    private final WarehouseInputService inputs = mock(WarehouseInputService.class);
    private final SupplierRepository suppliers = mock(SupplierRepository.class);
    private final WarehouseRepository warehouses = mock(WarehouseRepository.class);
    private final CurrentOrganization organization = mock(CurrentOrganization.class);
    private final OperationalWarehousePrintService printing = mock(OperationalWarehousePrintService.class);
    private final WarehouseInputReportService service = new WarehouseInputReportService(inputs, suppliers, warehouses, organization, printing);

    @Test
    void enrichesOnlyTheRequestedPageWithCompanyAndStoreScopedBulkQueries() {
        var company = mock(Company.class);
        var store = mock(Store.class);
        var companyId = UUID.randomUUID();
        var storeId = UUID.randomUUID();
        when(company.getId()).thenReturn(companyId);
        when(store.getId()).thenReturn(storeId);
        when(organization.currentCompany()).thenReturn(company);
        when(organization.currentStore()).thenReturn(store);
        var supplier = mock(Supplier.class);
        var supplierId = UUID.randomUUID();
        when(supplier.getId()).thenReturn(supplierId);
        when(supplier.getSupplierId()).thenReturn("P-000001");
        when(supplier.getLegalName()).thenReturn("PROVEEDOR SL");
        var warehouse = Warehouse.general(storeId);
        var first = mock(WarehouseInputView.class);
        var second = mock(WarehouseInputView.class);
        for (var input : List.of(first, second)) {
            when(input.supplierId()).thenReturn(supplierId);
            when(input.warehouseId()).thenReturn(warehouse.getId());
        }
        var from = LocalDate.of(2026, 8, 1);
        var to = LocalDate.of(2026, 8, 31);
        var type = WarehouseInputDocumentType.FACTURA_ENTRADA;
        when(inputs.listPage(50, "cursor", type, from, to))
                .thenReturn(new PagedResult<>(List.of(first, second), "next", true));
        when(suppliers.findByCompanyIdAndIdIn(companyId, List.of(supplierId))).thenReturn(List.of(supplier));
        when(warehouses.findByStoreIdAndIdIn(storeId, List.of(warehouse.getId()))).thenReturn(List.of(warehouse));

        var page = service.listPage(type, 50, "cursor", from, to, auth("GESTION_CUENTAS"));

        assertThat(page.nextCursor()).isEqualTo("next");
        assertThat(page.hasMore()).isTrue();
        assertThat(page.items()).extracting(WarehouseInputReportView::document).containsExactly(first, second);
        assertThat(page.items()).allSatisfy(value -> {
            assertThat(value.supplierCode()).isEqualTo("P-000001");
            assertThat(value.supplierName()).isEqualTo("PROVEEDOR SL");
            assertThat(value.warehouseName()).isEqualTo("GENERAL");
        });
        verify(inputs).listPage(50, "cursor", type, from, to);
        verify(suppliers).findByCompanyIdAndIdIn(companyId, List.of(supplierId));
        verify(warehouses).findByStoreIdAndIdIn(storeId, List.of(warehouse.getId()));
    }

    @ParameterizedTest
    @CsvSource({"FACTURA_ENTRADA,GESTION_PRODUCTO", "FACTURA_ENTRADA,GESTION_CUENTAS",
            "ALBARAN_ENTRADA,GESTION_PRODUCTO", "ALBARAN_ENTRADA,GESTION_CUENTAS",
            "ENTRADA_ALMACEN,GESTION_ALMACEN", "FACTURA_ENTRADA,ROLE_ADMIN"})
    void permitsTheAppropriateReadRoleWithoutGrantingOperationalAccess(WarehouseInputDocumentType type, String permission) {
        when(inputs.listPage(50, null, type, null, null)).thenReturn(new PagedResult<>(List.of(), null, false));
        assertThat(service.listPage(type, 50, null, null, null, auth(permission)).items()).isEmpty();
        verifyNoInteractions(suppliers, warehouses, organization);
    }

    @ParameterizedTest
    @CsvSource({"FACTURA_ENTRADA,VENTA", "FACTURA_ENTRADA,INVOICES_READ",
            "ALBARAN_ENTRADA,DELIVERY_NOTES_READ", "ENTRADA_ALMACEN,GESTION_CUENTAS",
            "ENTRADA_ALMACEN,GESTION_PRODUCTO"})
    void rejectsUnauthorizedTypesBeforeReadingAnything(WarehouseInputDocumentType type, String permission) {
        assertThatThrownBy(() -> service.listPage(type, 50, null, null, null, auth(permission)))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(inputs, suppliers, warehouses, organization);
    }

    @Test
    void missingReferencesStayNullInsteadOfLeakingIdsOrOtherTenants() {
        var input = mock(WarehouseInputView.class);
        when(inputs.listPage(10, null, WarehouseInputDocumentType.ALBARAN_ENTRADA, null, null))
                .thenReturn(new PagedResult<>(List.of(input), null, false));
        var row = service.listPage(WarehouseInputDocumentType.ALBARAN_ENTRADA, 10, null, null, null,
                auth("GESTION_ALMACEN")).items().getFirst();
        assertThat(row.supplierCode()).isNull();
        assertThat(row.supplierName()).isNull();
        assertThat(row.warehouseName()).isNull();
        verifyNoInteractions(suppliers, warehouses, organization);
    }

    @Test
    void rejectsMissingTypeWithoutQuerying() {
        assertThatThrownBy(() -> service.listPage(null, 50, null, null, null, auth("ROLE_ADMIN")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tipo");
        verifyNoInteractions(inputs, suppliers, warehouses, organization);
    }

    @ParameterizedTest
    @CsvSource({"FACTURA_ENTRADA,GESTION_CUENTAS", "ALBARAN_ENTRADA,GESTION_PRODUCTO",
            "ENTRADA_ALMACEN,GESTION_ALMACEN"})
    void printsTheScopedPersistedEntryWithTheExistingJasperRenderer(WarehouseInputDocumentType type, String permission) {
        var id = UUID.randomUUID();
        var document = mock(WarehouseInputView.class);
        var rendered = mock(RenderedDocumentView.class);
        when(document.documentType()).thenReturn(type);
        when(inputs.view(id)).thenReturn(document);
        when(printing.input(id)).thenReturn(rendered);
        assertThat(service.printDocument(id, auth(permission))).isSameAs(rendered);
        verify(inputs).view(id);
        verify(printing).input(id);
    }

    @Test
    void purchaseReadersCannotPrintWarehouseOnlyEntriesById() {
        var id = UUID.randomUUID();
        var document = mock(WarehouseInputView.class);
        when(document.documentType()).thenReturn(WarehouseInputDocumentType.ENTRADA_ALMACEN);
        when(inputs.view(id)).thenReturn(document);
        assertThatThrownBy(() -> service.printDocument(id, auth("GESTION_CUENTAS")))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(printing);
    }

    @Test
    void printingDoesNotReadOrRenderForUnrelatedSalesReaders() {
        assertThatThrownBy(() -> service.printDocument(UUID.randomUUID(), auth("INVOICES_READ")))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(inputs, printing);
    }

    private static UsernamePasswordAuthenticationToken auth(String authority) {
        return new UsernamePasswordAuthenticationToken("reader", "unused", List.of(new SimpleGrantedAuthority(authority)));
    }
}
