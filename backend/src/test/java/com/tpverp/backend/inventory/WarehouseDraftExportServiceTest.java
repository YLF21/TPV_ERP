package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import jakarta.validation.Validation;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class WarehouseDraftExportServiceTest {
    @Test
    void preservesLongUnknownImportedProductInsteadOfRejectingItsInvalidRow() throws Exception {
        var organization = mock(CurrentOrganization.class);
        var store = mock(Store.class);
        when(organization.currentStore()).thenReturn(store);
        when(store.getLocale()).thenReturn("es");
        when(store.getTimezone()).thenReturn("Atlantic/Canary");
        var importedProduct = "DESCONOCIDO-" + "X".repeat(600);
        var request = new WarehouseDraftExportController.Request("es", null, "Salida de almacén",
                "", "Borrador", null, "", List.of(new WarehouseDraftExportController.Line(
                1, importedProduct, importedProduct, BigDecimal.ONE, "Producto no encontrado")));
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(request)).isEmpty();
        }
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(
                new WarehouseDraftExportService(organization).export(request, false, null)))) {
            var sheet = workbook.getSheetAt(0);
            int header = -1;
            for (var row : sheet) {
                if (row.getCell(0) != null && row.getCell(0).getCellType() == CellType.STRING
                        && "Fila".equals(row.getCell(0).getStringCellValue())) header = row.getRowNum();
            }
            assertThat(sheet.getRow(header + 1).getCell(1).getStringCellValue()).isEqualTo(importedProduct);
            assertThat(sheet.getRow(header + 1).getCell(2).getStringCellValue()).isEqualTo(importedProduct);
            assertThat(sheet.getRow(header + 1).getCell(4).getStringCellValue()).isEqualTo("Producto no encontrado");
        }
    }

    @Test
    void keepsUnsavedInvalidRowsAndNumericQuantitiesInStyledWorkbook() throws Exception {
        var organization = mock(CurrentOrganization.class);
        var store = mock(Store.class);
        var company = mock(Company.class);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(company);
        when(store.getLocale()).thenReturn("es");
        when(store.getTimezone()).thenReturn("Atlantic/Canary");
        when(store.getNombreEfectivo()).thenReturn("Tienda");
        when(company.getRazonSocial()).thenReturn("Empresa");
        var request = new WarehouseDraftExportController.Request("es", null, "Salida de almacén",
                "SAL-1", "Borrador", LocalDate.of(2026, 11, 15), "GENERAL", List.of(
                new WarehouseDraftExportController.Line(1, "A001", "Café", new BigDecimal("2.500"), "Correcto"),
                new WarehouseDraftExportController.Line(2, "=UNKNOWN", "Sin catálogo", BigDecimal.ZERO, "Producto no encontrado"),
                new WarehouseDraftExportController.Line(3, "A003", "Cantidad pendiente", null, "Cantidad inválida")));

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(
                new WarehouseDraftExportService(organization).export(request, false, null)))) {
            var sheet = workbook.getSheet("Salida");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("SALIDA DE ALMACÉN");
            int header = -1;
            for (var row : sheet) {
                if (row.getCell(0) != null && row.getCell(0).getCellType() == CellType.STRING
                        && "Fila".equals(row.getCell(0).getStringCellValue())) header = row.getRowNum();
            }
            assertThat(header).isPositive();
            var first = sheet.getRow(header + 1);
            var invalid = sheet.getRow(header + 2);
            var pending = sheet.getRow(header + 3);
            assertThat(first.getCell(3).getNumericCellValue()).isEqualTo(2.5);
            assertThat(invalid.getCell(0).getNumericCellValue()).isEqualTo(2);
            assertThat(invalid.getCell(1).getCellType()).isEqualTo(CellType.STRING);
            assertThat(invalid.getCell(1).getStringCellValue()).isEqualTo("=UNKNOWN");
            assertThat(invalid.getCell(3).getNumericCellValue()).isZero();
            assertThat(invalid.getCell(4).getStringCellValue()).isEqualTo("Producto no encontrado");
            assertThat(pending.getCell(3)).isNull();
            assertThat(pending.getCell(4).getStringCellValue()).isEqualTo("Cantidad inválida");
            assertThat(first.getCell(1).getCellStyle().getFillPattern()).isEqualTo(
                    org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
            assertThat(sheet.getCTWorksheet().isSetAutoFilter()).isTrue();
        }
    }

    @Test
    void inputPurchaseReaderMayExportPurchasesButNotWarehouseInputsOrSalesOnly() throws Exception {
        var organization = mock(CurrentOrganization.class);
        var store = mock(Store.class);
        when(organization.currentStore()).thenReturn(store);
        when(store.getLocale()).thenReturn("es");
        when(store.getTimezone()).thenReturn("Atlantic/Canary");
        var service = new WarehouseDraftExportService(organization);
        var reader = auth("GESTION_CUENTAS");
        var salesOnly = auth("GESTION_VENTAS");
        var lines = List.of(new WarehouseDraftExportController.Line(1, "A001", "Producto", BigDecimal.ONE, "Correcto"));
        var purchase = new WarehouseDraftExportController.Request("es", WarehouseInputDocumentType.FACTURA_ENTRADA,
                "Factura de compra", "FE-1", "Borrador", null, "GENERAL", lines);
        var warehouse = new WarehouseDraftExportController.Request("es", WarehouseInputDocumentType.ENTRADA_ALMACEN,
                "Entrada de almacén", "ENT-1", "Borrador", null, "GENERAL", lines);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.export(purchase, true, reader)))) {
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("FACTURA DE COMPRA");
        }
        assertThatThrownBy(() -> service.export(warehouse, true, reader)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.export(purchase, true, salesOnly)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.export(purchase, true, null)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void controllerPreauthorizationMatchesStoredDocumentReadBoundary() throws Exception {
        var input = WarehouseDraftExportController.class.getMethod("input", WarehouseDraftExportController.Request.class, Authentication.class);
        var output = WarehouseDraftExportController.class.getMethod("output", WarehouseDraftExportController.Request.class);
        assertThat(input.getAnnotation(PreAuthorize.class).value()).isEqualTo(
                "hasRole('ADMIN') or hasAnyAuthority('GESTION_PRODUCTO','GESTION_ALMACEN','GESTION_CUENTAS')");
        assertThat(output.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('ADMIN') or hasAuthority('GESTION_ALMACEN')");
    }

    private static Authentication auth(String authority) {
        return new UsernamePasswordAuthenticationToken("reader", "unused",
                List.of(new SimpleGrantedAuthority(authority)));
    }
}
