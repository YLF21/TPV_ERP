package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.Warehouse;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.inventory.WarehouseInputRepository;
import com.tpverp.backend.inventory.WarehouseOutput;
import com.tpverp.backend.inventory.WarehouseOutputLine;
import com.tpverp.backend.inventory.WarehouseOutputRepository;
import com.tpverp.backend.inventory.WarehouseOutputStatus;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.junit.jupiter.api.Test;

class WarehouseDocumentExcelExportServiceTest {

    @Test
    void appliesTheCompactBoldLayoutToWarehouseOutputs() throws Exception {
        var inputs = mock(WarehouseInputRepository.class);
        var outputs = mock(WarehouseOutputRepository.class);
        var products = mock(ProductRepository.class);
        var warehouses = mock(WarehouseRepository.class);
        var organization = mock(CurrentOrganization.class);
        var company = new Company("B12345678", "EMPRESA PRUEBAS SL", address());
        var store = new Store(
                company, "001", "TIENDA PRUEBAS 001", address(), "address-hash",
                "Europe/Madrid", "EUR", "es-ES");
        var documentId = UUID.randomUUID();
        var warehouseId = UUID.randomUUID();
        var productId = UUID.randomUUID();
        var output = mock(WarehouseOutput.class);
        var line = mock(WarehouseOutputLine.class);
        var product = mock(Product.class);
        var warehouse = mock(Warehouse.class);

        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(company);
        when(outputs.findById(documentId)).thenReturn(Optional.of(output));
        when(output.getStoreId()).thenReturn(store.getId());
        when(output.getWarehouseId()).thenReturn(warehouseId);
        when(output.getNumber()).thenReturn("SAL-2026-000001");
        when(output.getDate()).thenReturn(LocalDate.of(2026, 8, 9));
        when(output.getStatus()).thenReturn(WarehouseOutputStatus.CONFIRMADA);
        when(output.getLines()).thenReturn(List.of(line));
        when(line.getProductId()).thenReturn(productId);
        when(line.getQuantity()).thenReturn(1);
        when(line.getSaleUnitPrice()).thenReturn(new BigDecimal("12.10"));
        when(line.getSaleTotal()).thenReturn(new BigDecimal("12.10"));
        when(products.findAllByStoreIdAndIdIn(store.getId(), List.of(productId))).thenReturn(List.of(product));
        when(product.getId()).thenReturn(productId);
        when(product.getCode()).thenReturn("DEV-CAFE");
        when(product.getName()).thenReturn("Cafe molido pruebas");
        when(warehouses.findById(warehouseId)).thenReturn(Optional.of(warehouse));
        when(warehouse.getName()).thenReturn("GENERAL");
        var service = new WarehouseDocumentExcelExportService(
                inputs, outputs, products, warehouses, organization);

        var bytes = service.exportOutput(documentId);

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("EMPRESA PRUEBAS SL");
            assertThat(sheet.getRow(0).getCell(3).getStringCellValue()).isEqualTo("Documento");
            assertThat(sheet.getRow(0).getCell(4).getStringCellValue()).isEqualTo("Salida de almacén");
            assertThat(sheet.getRow(6).getCell(0).getStringCellValue()).isEqualTo("Código");
            assertThat(sheet.getRow(7).getCell(0).getStringCellValue()).isEqualTo("DEV-CAFE");
            assertThat(workbook.getFontAt(sheet.getRow(0).getCell(0).getCellStyle().getFontIndexAsInt()).getBold())
                    .isTrue();
            assertThat(workbook.getFontAt(sheet.getRow(6).getCell(0).getCellStyle().getFontIndexAsInt()).getBold())
                    .isTrue();
            assertThat(sheet.getRow(0).getCell(0).getCellStyle().getFillPattern())
                    .isEqualTo(FillPatternType.NO_FILL);
            assertThat(sheet.getRow(0).getCell(1).getCellStyle().getFillPattern())
                    .isEqualTo(FillPatternType.NO_FILL);
            assertThat(workbook.getFontAt(sheet.getRow(0).getCell(1).getCellStyle().getFontIndexAsInt()).getBold())
                    .isTrue();
            assertThat(sheet.getRow(6).getCell(0).getCellStyle().getFillPattern())
                    .isEqualTo(FillPatternType.NO_FILL);
            assertThat(sheet.getRow(0).getCell(0).getCellStyle().getBorderTop())
                    .isEqualTo(BorderStyle.THIN);
            assertThat(sheet.getRow(0).getCell(4).getCellStyle().getBorderRight())
                    .isEqualTo(BorderStyle.THIN);
            assertThat(sheet.getRow(6).getCell(4).getCellStyle().getBorderBottom())
                    .isEqualTo(BorderStyle.THIN);
            assertThat(sheet.getRow(9).getCell(0).getCellStyle().getFillPattern())
                    .isEqualTo(FillPatternType.NO_FILL);
            assertThat(workbook.getFontAt(sheet.getRow(9).getCell(0).getCellStyle().getFontIndexAsInt()).getBold())
                    .isTrue();
            assertThat(sheet.getPaneInformation()).isNotNull();
        }
    }

    private static java.util.Map<String, String> address() {
        return java.util.Map.of(
                "linea1", "Calle Pruebas 1",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES");
    }
}
