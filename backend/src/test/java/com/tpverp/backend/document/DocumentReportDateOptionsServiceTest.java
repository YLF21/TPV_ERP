package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tpverp.backend.inventory.WarehouseInputRepository;
import com.tpverp.backend.inventory.WarehouseInputDocumentType;
import com.tpverp.backend.inventory.WarehouseOutputRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class DocumentReportDateOptionsServiceTest {
    private final CommercialDocumentRepository documents = mock(CommercialDocumentRepository.class);
    private final WarehouseInputRepository inputs = mock(WarehouseInputRepository.class);
    private final WarehouseOutputRepository outputs = mock(WarehouseOutputRepository.class);
    private final CurrentOrganization organization = mock(CurrentOrganization.class);
    private final UUID storeId = UUID.randomUUID();
    private final DocumentReportDateOptionsService service = new DocumentReportDateOptionsService(
            documents, inputs, outputs, organization,
            Clock.fixed(Instant.parse("2026-09-11T23:30:00Z"), ZoneOffset.UTC));

    DocumentReportDateOptionsServiceTest() {
        var store = mock(Store.class);
        when(store.getId()).thenReturn(storeId);
        when(store.getTimezone()).thenReturn("Atlantic/Canary");
        when(organization.currentStore()).thenReturn(store);
    }

    @Test
    void usesTheStoreDayAndTheMinimumOfTheSelectedDocumentTypes() {
        var earliest = LocalDate.of(2024, 2, 29);
        var types = EnumSet.of(CommercialDocumentType.FACTURA_VENTA, CommercialDocumentType.RECTIFICATIVA_VENTA);
        when(documents.findFirstReportDate(storeId, types)).thenReturn(earliest);
        var result = service.options("invoices", auth("INVOICES_READ"));
        assertThat(result.earliestDate()).isEqualTo(earliest);
        assertThat(result.currentDate()).isEqualTo(LocalDate.of(2026, 9, 12));
        verify(documents).findFirstReportDate(storeId, types);
        verifyNoInteractions(inputs, outputs);
    }

    @ParameterizedTest
    @CsvSource({"tickets,TICKETS_READ", "invoices,INVOICES_READ", "deliveryNotes,DELIVERY_NOTES_READ",
            "warehouseOutputs,GESTION_ALMACEN", "inputWarehouse,GESTION_ALMACEN",
            "inputInvoices,GESTION_CUENTAS", "inputDeliveryNotes,GESTION_PRODUCTO"})
    void emptyReportsUseTodayAndKeepSpecificReadPermissions(String report, String permission) {
        var result = service.options(report, auth(permission));
        assertThat(result.earliestDate()).isEqualTo(result.currentDate());
    }

    @ParameterizedTest
    @CsvSource({"tickets,GESTION_ALMACEN", "invoices,TICKETS_READ", "deliveryNotes,INVOICES_READ",
            "warehouseOutputs,VENTA", "inputWarehouse,VENTA",
            "inputInvoices,INVOICES_READ", "inputDeliveryNotes,DELIVERY_NOTES_READ"})
    void rejectsForeignReportPermissionsBeforeAccessingAnyData(String report, String permission) {
        assertThatThrownBy(() -> service.options(report, auth(permission)))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(documents, inputs, outputs);
    }

    @Test
    void warehouseMinimumDoesNotComeFromTheSalesBook() {
        var earliest = LocalDate.of(2020, 1, 1);
        when(outputs.findFirstReportDate(storeId)).thenReturn(earliest);
        assertThat(service.options("warehouseOutputs", auth("ROLE_ADMIN")).earliestDate()).isEqualTo(earliest);
        verifyNoInteractions(documents, inputs);
    }

    @ParameterizedTest
    @CsvSource({"inputInvoices,FACTURA_ENTRADA", "inputDeliveryNotes,ALBARAN_ENTRADA", "inputWarehouse,ENTRADA_ALMACEN"})
    void entryDateOptionsUseOnlyTheirCurrentWarehouseInputType(String report, WarehouseInputDocumentType type) {
        var earliest = LocalDate.of(2022, 8, 15);
        when(inputs.findFirstReportDate(storeId, type)).thenReturn(earliest);
        assertThat(service.options(report, auth("GESTION_ALMACEN")).earliestDate()).isEqualTo(earliest);
        verify(inputs).findFirstReportDate(storeId, type);
        verifyNoInteractions(documents, outputs);
    }

    @Test
    void rejectsUnknownReportKeys() {
        assertThatThrownBy(() -> service.options("other", auth("ROLE_ADMIN")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(documents, inputs, outputs);
    }

    private static UsernamePasswordAuthenticationToken auth(String permission) {
        return new UsernamePasswordAuthenticationToken("user", "unused", List.of(new SimpleGrantedAuthority(permission)));
    }
}
