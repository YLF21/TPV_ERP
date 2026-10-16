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
import com.tpverp.backend.inventory.WarehouseTransferDocumentRepository;
import com.tpverp.backend.inventory.StockAdjustmentHistoryRepository;
import com.tpverp.backend.inventory.StockCountRepository;
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
    private final WarehouseTransferDocumentRepository transfers = mock(WarehouseTransferDocumentRepository.class);
    private final StockAdjustmentHistoryRepository adjustments = mock(StockAdjustmentHistoryRepository.class);
    private final StockCountRepository counts = mock(StockCountRepository.class);
    private final CurrentOrganization organization = mock(CurrentOrganization.class);
    private final UUID storeId = UUID.randomUUID();
    private final DocumentReportDateOptionsService service = new DocumentReportDateOptionsService(
            documents, inputs, outputs, transfers, adjustments, counts, organization,
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
        verifyNoInteractions(inputs, outputs, transfers, adjustments, counts);
    }

    @ParameterizedTest
    @CsvSource({"topSales,STOCK_READ", "topSales,GESTION_PRODUCTO", "topSales,GESTION_VENTAS", "topSales,VENTA", "tickets,TICKETS_READ", "invoices,INVOICES_READ", "deliveryNotes,DELIVERY_NOTES_READ",
            "warehouseOutputs,GESTION_ALMACEN", "inputWarehouse,GESTION_ALMACEN",
            "warehouseTransfers,STOCK_TRANSFER", "warehouseTransfers,GESTION_ALMACEN", "warehouseTransfers,ROLE_ADMIN",
            "stockAdjustments,STOCK_ADJUST", "stockAdjustments,GESTION_ALMACEN", "stockAdjustments,ROLE_ADMIN",
            "stockCounts,STOCK_ADJUST", "stockCounts,GESTION_ALMACEN", "stockCounts,ROLE_ADMIN",
            "inputInvoices,GESTION_CUENTAS", "inputDeliveryNotes,GESTION_PRODUCTO"})
    void emptyReportsUseTodayAndKeepSpecificReadPermissions(String report, String permission) {
        var result = service.options(report, auth(permission));
        assertThat(result.earliestDate()).isEqualTo(result.currentDate());
    }

    @ParameterizedTest
    @CsvSource({"topSales,GESTION_ALMACEN", "topSales,TICKETS_READ", "tickets,GESTION_ALMACEN", "invoices,TICKETS_READ", "deliveryNotes,INVOICES_READ",
            "warehouseOutputs,VENTA", "inputWarehouse,VENTA",
            "warehouseTransfers,STOCK_ADJUST", "warehouseTransfers,VENTA",
            "stockAdjustments,STOCK_TRANSFER", "stockAdjustments,VENTA",
            "stockCounts,STOCK_TRANSFER", "stockCounts,VENTA",
            "inputInvoices,INVOICES_READ", "inputDeliveryNotes,DELIVERY_NOTES_READ"})
    void rejectsForeignReportPermissionsBeforeAccessingAnyData(String report, String permission) {
        assertThatThrownBy(() -> service.options(report, auth(permission)))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(documents, inputs, outputs, transfers, adjustments, counts, organization);
    }

    @Test
    void warehouseMinimumDoesNotComeFromTheSalesBook() {
        var earliest = LocalDate.of(2020, 1, 1);
        when(outputs.findFirstReportDate(storeId)).thenReturn(earliest);
        assertThat(service.options("warehouseOutputs", auth("ROLE_ADMIN")).earliestDate()).isEqualTo(earliest);
        verifyNoInteractions(documents, inputs, transfers, adjustments, counts);
    }

    @ParameterizedTest
    @CsvSource({"inputInvoices,FACTURA_ENTRADA", "inputDeliveryNotes,ALBARAN_ENTRADA", "inputWarehouse,ENTRADA_ALMACEN"})
    void entryDateOptionsUseOnlyTheirCurrentWarehouseInputType(String report, WarehouseInputDocumentType type) {
        var earliest = LocalDate.of(2022, 8, 15);
        when(inputs.findFirstReportDate(storeId, type)).thenReturn(earliest);
        assertThat(service.options(report, auth("GESTION_ALMACEN")).earliestDate()).isEqualTo(earliest);
        verify(inputs).findFirstReportDate(storeId, type);
        verifyNoInteractions(documents, outputs, transfers, adjustments, counts);
    }

    @Test
    void topSalesDatesCoverAllRankingDocumentTypes() {
        var types = EnumSet.of(CommercialDocumentType.TICKET, CommercialDocumentType.ALBARAN_VENTA,
                CommercialDocumentType.FACTURA_VENTA, CommercialDocumentType.RECTIFICATIVA_VENTA);
        var earliest = LocalDate.of(2021, 3, 4);
        when(documents.findFirstReportDate(storeId, types)).thenReturn(earliest);
        assertThat(service.options("topSales", auth("STOCK_READ")).earliestDate()).isEqualTo(earliest);
        verify(documents).findFirstReportDate(storeId, types);
        verifyNoInteractions(inputs, outputs, transfers, adjustments, counts);
    }

    @Test
    void rejectsUnknownReportKeys() {
        assertThatThrownBy(() -> service.options("other", auth("ROLE_ADMIN")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(documents, inputs, outputs, transfers, adjustments, counts, organization);
    }

    @Test
    void warehouseTransferMinimumUsesOnlyTheCurrentStoreAndConvertsMidnightToStoreDate() {
        var first = Instant.parse("2024-07-01T23:30:00Z");
        when(transfers.findFirstReportCreatedAt(storeId)).thenReturn(first);
        var result = service.options("warehouseTransfers", auth("STOCK_TRANSFER"));
        assertThat(result.earliestDate()).isEqualTo(LocalDate.of(2024, 7, 2));
        assertThat(result.currentDate()).isEqualTo(LocalDate.of(2026, 9, 12));
        verify(transfers).findFirstReportCreatedAt(storeId);
        verifyNoInteractions(documents, inputs, outputs, adjustments, counts);
    }

    @Test
    void stockAdjustmentMinimumUsesOnlyTheCurrentStoreAndConvertsMidnightToStoreDate() {
        when(adjustments.findFirstReportCreatedAt(storeId)).thenReturn(Instant.parse("2024-07-01T23:30:00Z"));
        assertThat(service.options("stockAdjustments", auth("STOCK_ADJUST")).earliestDate())
                .isEqualTo(LocalDate.of(2024, 7, 2));
        verify(adjustments).findFirstReportCreatedAt(storeId);
        verifyNoInteractions(documents, inputs, outputs, transfers, counts);
    }

    @Test
    void stockCountMinimumUsesTheDocumentDateForTheCurrentStore() {
        when(counts.findFirstReportDate(storeId)).thenReturn(LocalDate.of(2023, 12, 31));
        assertThat(service.options("stockCounts", auth("STOCK_ADJUST")).earliestDate())
                .isEqualTo(LocalDate.of(2023, 12, 31));
        verify(counts).findFirstReportDate(storeId);
        verifyNoInteractions(documents, inputs, outputs, transfers, adjustments);
    }

    @Test
    void newWarehouseReportsClampFutureMinimumsToStoreToday() {
        when(transfers.findFirstReportCreatedAt(storeId)).thenReturn(Instant.parse("2030-01-01T00:00:00Z"));
        when(adjustments.findFirstReportCreatedAt(storeId)).thenReturn(Instant.parse("2030-01-01T00:00:00Z"));
        when(counts.findFirstReportDate(storeId)).thenReturn(LocalDate.of(2030, 1, 1));
        for (var report : List.of("warehouseTransfers", "stockAdjustments", "stockCounts")) {
            var result = service.options(report, auth("ROLE_ADMIN"));
            assertThat(result.earliestDate()).isEqualTo(LocalDate.of(2026, 9, 12));
            assertThat(result.currentDate()).isEqualTo(LocalDate.of(2026, 9, 12));
        }
    }

    private static UsernamePasswordAuthenticationToken auth(String permission) {
        return new UsernamePasswordAuthenticationToken("user", "unused", List.of(new SimpleGrantedAuthority(permission)));
    }
}
