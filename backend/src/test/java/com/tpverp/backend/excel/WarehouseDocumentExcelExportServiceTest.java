package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.Warehouse;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.inventory.WarehouseInputRepository;
import com.tpverp.backend.inventory.WarehouseInput;
import com.tpverp.backend.inventory.WarehouseInputDocumentType;
import com.tpverp.backend.inventory.WarehouseInputLineCommand;
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
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.access.AccessDeniedException;

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
        when(line.getQuantity()).thenReturn(10);
        when(line.getSaleUnitPrice()).thenReturn(new BigDecimal("2.208"));
        when(line.getSaleTotal()).thenReturn(new BigDecimal("22.08"));
        when(line.getProductId()).thenReturn(productId);
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
            int header = sheet.getPaneInformation().getHorizontalSplitPosition() - 1;
            var data = sheet.getRow(header + 1);
            var total = sheet.getRow(sheet.getLastRowNum());
            assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("SALIDA DE ALMACÉN");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("EMPRESA PRUEBAS SL");
            assertThat(sheet.getRow(header).getCell(0).getStringCellValue()).isEqualTo("Código");
            assertThat(data.getCell(0).getStringCellValue()).isEqualTo("DEV-CAFE");
            assertThat(data.getCell(2).getNumericCellValue()).isEqualTo(10);
            assertThat(data.getCell(3).getNumericCellValue()).isEqualTo(2.208);
            assertThat(data.getCell(3).getCellStyle().getDataFormatString()).contains("0.00#");
            assertThat(data.getCell(4).getNumericCellValue()).isEqualTo(22.08);
            assertThat(data.getCell(4).getCellStyle().getDataFormatString()).doesNotContain("0.00#");
            assertThat(total.getCell(4).getNumericCellValue()).isEqualTo(22.08);
            assertThat(workbook.getFontAt(data.getCell(1).getCellStyle().getFontIndex()).getFontName()).isEqualTo("Calibri");
            assertThat(data.getCell(1).getCellStyle().getWrapText()).isTrue();
            assertThat(sheet.getRow(0).getCell(0).getCellStyle().getFillPattern()).isEqualTo(FillPatternType.SOLID_FOREGROUND);
            assertThat(sheet.getRow(header).getCell(0).getCellStyle().getFillPattern()).isEqualTo(FillPatternType.SOLID_FOREGROUND);
            assertThat(workbook.getFontAt(total.getCell(4).getCellStyle().getFontIndex()).getBold()).isTrue();
            var path = java.nio.file.Path.of("target", "warehouse-design", "Salida-individual.xlsx");
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.write(path, bytes);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "FACTURA_ENTRADA,10.00,0.1,10%,90",
            "ALBARAN_ENTRADA,10.00,0.1,10%,90",
            "FACTURA_ENTRADA,0.00,0,0%,100",
            "FACTURA_ENTRADA,10.50,0.105,10.5%,89.50",
            "FACTURA_ENTRADA,10.25,0.1025,10.25%,89.75",
            "FACTURA_ENTRADA,100.00,1,100%,0"
    })
    void allowsPurchaseReadersToExportOnlyScopedPurchaseInputs(WarehouseInputDocumentType type,
            String discount, double ratio, String formatted, double total) throws Exception {
        var inputs = mock(WarehouseInputRepository.class);
        var products = mock(ProductRepository.class);
        var organization = mock(CurrentOrganization.class);
        var company = new Company("B12345678", "EMPRESA PRUEBAS SL", address());
        var store = new Store(company, "001", "TIENDA PRUEBAS", address(), "hash", "Europe/Madrid", "EUR", "es-ES");
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(company);
        var input = new WarehouseInput(store.getId(), UUID.randomUUID(), LocalDate.of(2026, 8, 10), UUID.randomUUID(), type);
        input.replace(null, "Origen", "REF-01", "Compra", null, new BigDecimal(discount), List.of(),
                List.of(new WarehouseInputLineCommand(UUID.randomUUID(), new BigDecimal("2"),
                        new BigDecimal("50"), BigDecimal.ZERO, false, "PRODUCTO")), null);
        when(inputs.findByIdAndStoreId(input.getId(), store.getId())).thenReturn(Optional.of(input));
        var service = new WarehouseDocumentExcelExportService(inputs, mock(WarehouseOutputRepository.class), products,
                mock(WarehouseRepository.class), organization);

        var bytes = service.exportInput(input.getId(), auth("GESTION_CUENTAS"));

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            int totalRow = sheet.getLastRowNum();
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("EMPRESA PRUEBAS SL");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo(
                    type == WarehouseInputDocumentType.FACTURA_ENTRADA ? "ENTRADA DE FACTURA" : "ENTRADA DE ALBARÁN");
            assertThat(sheet.getRow(totalRow - 2).getCell(0).getStringCellValue()).isEqualTo("Subtotal");
            assertThat(sheet.getRow(totalRow - 2).getCell(4).getNumericCellValue()).isEqualTo(100);
            assertThat(sheet.getRow(totalRow - 1).getCell(0).getStringCellValue()).isEqualTo("Descuento total del documento %");
            assertThat(sheet.getRow(totalRow - 1).getCell(4).getNumericCellValue()).isEqualTo(ratio);
            assertThat(new org.apache.poi.ss.usermodel.DataFormatter(Locale.US)
                    .formatCellValue(sheet.getRow(totalRow - 1).getCell(4))).isEqualTo(formatted);
            assertThat(new org.apache.poi.ss.usermodel.DataFormatter(Locale.forLanguageTag("es-ES"))
                    .formatCellValue(sheet.getRow(totalRow - 1).getCell(4))).isEqualTo(formatted.replace('.', ','));
            assertThat(sheet.getRow(totalRow).getCell(0).getStringCellValue()).isEqualTo("Total de compra");
            assertThat(sheet.getRow(totalRow).getCell(4).getNumericCellValue()).isEqualTo(total);
        }
        verify(inputs).findByIdAndStoreId(input.getId(), store.getId());
    }

    @Test
    void purchaseReaderCannotExportWarehouseOnlyEntriesOrAnotherStoresDocument() {
        var inputs = mock(WarehouseInputRepository.class);
        var products = mock(ProductRepository.class);
        var organization = mock(CurrentOrganization.class);
        var store = mock(Store.class);
        when(store.getId()).thenReturn(UUID.randomUUID());
        when(organization.currentStore()).thenReturn(store);
        var input = new WarehouseInput(store.getId(), UUID.randomUUID(), LocalDate.of(2026, 8, 10), UUID.randomUUID());
        when(inputs.findByIdAndStoreId(input.getId(), store.getId())).thenReturn(Optional.of(input));
        var service = new WarehouseDocumentExcelExportService(inputs, mock(WarehouseOutputRepository.class), products,
                mock(WarehouseRepository.class), organization);

        assertThatThrownBy(() -> service.exportInput(input.getId(), auth("GESTION_CUENTAS")))
                .isInstanceOf(AccessDeniedException.class);
        var foreignId = UUID.randomUUID();
        assertThatThrownBy(() -> service.exportInput(foreignId, auth("GESTION_ALMACEN")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no encontrada");
        verifyNoInteractions(products);
    }

    @Test
    void rejectsSalesOnlyReadersBeforeLookingUpAnIndividualEntry() {
        var inputs = mock(WarehouseInputRepository.class);
        var organization = mock(CurrentOrganization.class);
        var service = new WarehouseDocumentExcelExportService(inputs, mock(WarehouseOutputRepository.class),
                mock(ProductRepository.class), mock(WarehouseRepository.class), organization);
        assertThatThrownBy(() -> service.exportInput(UUID.randomUUID(), auth("INVOICES_READ")))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(inputs, organization);
    }

    private static UsernamePasswordAuthenticationToken auth(String permission) {
        return new UsernamePasswordAuthenticationToken("reader", "unused", List.of(new SimpleGrantedAuthority(permission)));
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
