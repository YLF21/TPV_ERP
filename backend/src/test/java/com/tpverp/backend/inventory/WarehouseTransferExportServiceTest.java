package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.Warehouse;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

class WarehouseTransferExportServiceTest {
    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
    private final UUID storeId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final Warehouse source = new Warehouse(storeId, "ORIGEN");
    private final Warehouse target = new Warehouse(storeId, "DESTINO");
    private final WarehouseRepository warehouses = mock(WarehouseRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final CurrentOrganization organization = mock(CurrentOrganization.class);
    private final Product product = mock(Product.class);
    private final WarehouseTransferExportService service = new WarehouseTransferExportService(
            warehouses, products, organization, VALIDATORS.getValidator());

    WarehouseTransferExportServiceTest() {
        var store = mock(Store.class);
        var company = mock(Company.class);
        when(store.getId()).thenReturn(storeId);
        when(store.getNombreEfectivo()).thenReturn("Tienda 1");
        when(store.getLocale()).thenReturn("es-ES");
        when(company.getRazonSocial()).thenReturn("Empresa");
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(company);
        when(warehouses.findByStoreIdAndIdIn(storeId, List.of(source.getId(), target.getId())))
                .thenReturn(List.of(source, target));
        when(product.getId()).thenReturn(productId);
        when(product.getName()).thenReturn("Nombre actual");
        when(product.getCode()).thenReturn("00001");
        when(product.getBarcode()).thenReturn("0012345678901");
        when(product.getPurchasePrice()).thenReturn(new BigDecimal("5.123"));
        when(products.findAllByStoreIdAndIdIn(storeId, List.of(productId))).thenReturn(List.of(product));
    }

    @AfterAll
    static void closeValidator() { VALIDATORS.close(); }

    @Test
    void exportsUnsavedDraftWithHistoricalNameAndInputDocumentRounding() throws Exception {
        var line = new WarehouseTransferExportController.Line(productId, new BigDecimal("2.500"),
                new BigDecimal("1.237"), new BigDecimal("10.00"), "=Nombre histórico", true);
        var request = request(source.getId(), List.of(line), "es", new BigDecimal("7.50"));

        try (var book = new XSSFWorkbook(new ByteArrayInputStream(service.export(request)))) {
            var sheet = book.getSheetAt(0);
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("Empresa");
            assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("Tienda 1");
            assertThat(sheet.getRow(3).getCell(1).getStringCellValue()).isEqualTo("2026-09-23");
            assertThat(sheet.getRow(4).getCell(1).getStringCellValue()).isEmpty();
            assertThat(sheet.getRow(5).getCell(1).getStringCellValue()).isEqualTo("Borrador");
            var row = sheet.getRow(13);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo("00001");
            assertThat(row.getCell(1).getStringCellValue()).isEqualTo("0012345678901");
            assertThat(row.getCell(2).getCellType()).isEqualTo(CellType.STRING);
            assertThat(row.getCell(2).getStringCellValue()).isEqualTo("=Nombre histórico");
            assertThat(row.getCell(4).getNumericCellValue()).isEqualTo(1.237);
            assertThat(row.getCell(5).getNumericCellValue()).isEqualTo(2.5);
            assertThat(row.getCell(6).getNumericCellValue()).isEqualTo(2.78);
            assertThat(sheet.getRow(15).getCell(6).getNumericCellValue()).isEqualTo(2.78);
            assertThat(sheet.getRow(16).getCell(6).getNumericCellValue()).isEqualTo(7.5);
            assertThat(sheet.getRow(17).getCell(6).getNumericCellValue()).isEqualTo(.21);
            assertThat(sheet.getRow(18).getCell(6).getNumericCellValue()).isEqualTo(2.57);
        }
        verify(products, never()).save(any());
        verify(warehouses, never()).save(any());
    }

    @Test
    void fallsBackToSelectedPriceAndLocalizesLabels() throws Exception {
        when(product.getSalePrice()).thenReturn(new BigDecimal("8.750"));
        var line = new WarehouseTransferExportController.Line(productId, BigDecimal.ONE, null, null, null, false);
        var request = new WarehouseTransferExportController.Request(source.getId(), target.getId(),
                LocalDate.of(2026, 9, 23), "EXT-1", "Notas", "TRA-1", "CONFIRMED",
                WarehouseInputPriceSource.SALE, null, List.of(line), "zh-CN");
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(service.export(request)))) {
            var sheet = book.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("调拨");
            assertThat(sheet.getRow(5).getCell(1).getStringCellValue()).isEqualTo("已确认");
            assertThat(sheet.getRow(10).getCell(1).getStringCellValue()).isEqualTo("零售价");
            assertThat(sheet.getRow(13).getCell(2).getStringCellValue()).isEqualTo("Nombre actual");
            assertThat(sheet.getRow(13).getCell(4).getNumericCellValue()).isEqualTo(8.75);
        }
    }

    @Test
    void rejectsWarehouseOutsideCurrentStore() {
        when(warehouses.findByStoreIdAndIdIn(any(), anyList())).thenReturn(List.of(source));
        assertThatThrownBy(() -> service.export(request(source.getId(), List.of(line()), "en", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Almacén");
        verifyNoInteractions(products);
    }

    @Test
    void rejectsProductOutsideCurrentStore() {
        when(products.findAllByStoreIdAndIdIn(any(), anyList())).thenReturn(List.of());
        assertThatThrownBy(() -> service.export(request(source.getId(), List.of(line()), "en", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Producto");
    }

    @Test
    void rejectsIdenticalWarehouses() {
        assertThatThrownBy(() -> service.export(request(target.getId(), List.of(line()), "es", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("distintos");
        verifyNoInteractions(warehouses, products, organization);
    }

    @Test
    void rejectsExcessLinesAndInvalidAmountsBeforeRepositoryReads() {
        assertThatThrownBy(() -> service.export(request(source.getId(), Collections.nCopies(5001, line()), "es", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.export(request(source.getId(), List.of(line()), "es", new BigDecimal("100.01"))))
                .isInstanceOf(IllegalArgumentException.class);
        var negative = new WarehouseTransferExportController.Line(productId, BigDecimal.ONE,
                new BigDecimal("-1"), BigDecimal.ZERO, null, false);
        assertThatThrownBy(() -> service.export(request(source.getId(), List.of(negative), "es", null)))
                .isInstanceOf(IllegalArgumentException.class);
        var excessivePrecision = new WarehouseTransferExportController.Line(productId, new BigDecimal("0.0001"),
                BigDecimal.ONE, BigDecimal.ZERO, null, false);
        assertThatThrownBy(() -> service.export(request(source.getId(), List.of(excessivePrecision), "es", null)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(warehouses, products, organization);
    }

    private WarehouseTransferExportController.Line line() {
        return new WarehouseTransferExportController.Line(productId, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ZERO, null, false);
    }

    private WarehouseTransferExportController.Request request(UUID sourceId,
            List<WarehouseTransferExportController.Line> lines, String locale, BigDecimal discount) {
        return new WarehouseTransferExportController.Request(sourceId, target.getId(), LocalDate.of(2026, 9, 23),
                "EXT-1", "Notas", null, null, WarehouseInputPriceSource.PURCHASE, discount, lines, locale);
    }
}
