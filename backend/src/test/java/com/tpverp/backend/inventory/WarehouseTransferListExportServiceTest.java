package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.tpverp.backend.catalog.*;
import com.tpverp.backend.organization.*;
import com.tpverp.backend.shared.api.PagedResult;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class WarehouseTransferListExportServiceTest {
    private final UUID storeId = UUID.randomUUID();
    private final Warehouse source = new Warehouse(storeId, "GENERAL");
    private final Warehouse target = new Warehouse(storeId, "DEPÓSITO");
    private final WarehouseTransferDocumentService transfers = mock(WarehouseTransferDocumentService.class);
    private final WarehouseRepository warehouses = mock(WarehouseRepository.class);
    private final CurrentOrganization organization = mock(CurrentOrganization.class);
    private final WarehouseTransferListExportService service = new WarehouseTransferListExportService(transfers, warehouses, organization);
    private final WarehouseTransferListExportController.Filters filters = new WarehouseTransferListExportController.Filters(
            WarehouseTransferDocument.Status.CONFIRMED, source.getId(), target.getId(),
            Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z"), "TRA", "es");

    WarehouseTransferListExportServiceTest() {
        var store = mock(Store.class);
        when(store.getId()).thenReturn(storeId); when(store.getTimezone()).thenReturn("Europe/Madrid");
        when(store.getNombreEfectivo()).thenReturn("Tienda de ejemplo"); when(organization.currentStore()).thenReturn(store);
        when(store.getDireccion()).thenReturn(Map.of("linea1", "Calle Mayor 1", "codigoPostal", "28001", "ciudad", "Madrid"));
        var company = mock(Company.class);
        when(company.getRazonSocial()).thenReturn("Empresa Ejemplo");
        when(company.getTaxId()).thenReturn("B12345678");
        when(organization.currentCompany()).thenReturn(company);
        when(warehouses.findByStoreIdOrderByNombre(storeId)).thenReturn(List.of(source, target));
        when(transfers.listFiltered(anyInt(), eq(100), eq(List.of(filters.status())), eq(List.of(source.getId())), eq(List.of(target.getId())),
                eq(filters.from()), eq(filters.before()), eq("TRA"))).thenAnswer(invocation -> {
            int page = invocation.getArgument(0);
            return new PagedResult<>(IntStream.range(page * 100, Math.min(120, page * 100 + 100))
                    .mapToObj(this::row).toList(), page == 0 ? "1" : null, page == 0);
        });
    }

    private WarehouseTransferDocumentService.ListItem row(int index) {
        return new WarehouseTransferDocumentService.ListItem(UUID.randomUUID(), storeId, source.getId(), target.getId(),
                "TRA-2026-" + String.format("%06d", index), WarehouseTransferDocument.Status.CONFIRMED,
                index == 1 ? "=Observación extensa y acentuada. ".repeat(30) : "Reposición de productos",
                Instant.parse("2026-09-23T10:45:00Z"), 1, 2, new BigDecimal("18.500"));
    }

    @Test void exportsAllBatchesWithExactFiltersAndNumericExcelCells() throws Exception {
        byte[] bytes = service.export("xlsx", filters);
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = book.getSheetAt(0);
            int first = sheet.getPaneInformation().getHorizontalSplitPosition();
            assertThat(sheet.getLastRowNum()).isEqualTo(first + 119);
            assertThat(sheet.getRow(first + 119).getCell(0).getStringCellValue()).isEqualTo("TRA-2026-000119");
            assertThat(sheet.getRow(first).getCell(2).getStringCellValue()).isEqualTo("GENERAL");
            assertThat(sheet.getRow(first).getCell(3).getStringCellValue()).isEqualTo("DEPÓSITO");
            assertThat(sheet.getRow(first).getCell(7).getNumericCellValue()).isEqualTo(18.5);
            assertThat(sheet.getRow(first + 1).getCell(4).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Traspasos de almacén");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("Empresa Ejemplo");
            assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("B12345678");
        }
        verify(transfers).listFiltered(1, 100, List.of(filters.status()), List.of(source.getId()), List.of(target.getId()), filters.from(), filters.before(), "TRA");
        saveArtifact("traspasos.xlsx", bytes);
    }

    @Test void pdfIncludesAllRowsAndRepeatsHeadersAcrossPages() throws Exception {
        byte[] bytes = service.export("pdf", filters);
        try (var pdf = Loader.loadPDF(bytes)) {
            assertThat(pdf.getNumberOfPages()).isGreaterThan(1);
            String text = new PDFTextStripper().getText(pdf);
            assertThat(text).contains("TRA-2026-000000", "TRA-2026-000119", "GENERAL", "DEPÓSITO", "18,5");
            assertThat(text.split("Traspasos de almacén", -1).length - 1).isEqualTo(pdf.getNumberOfPages());
            assertThat(text).contains("Página 1 de " + pdf.getNumberOfPages());
            var firstPage = new PDFTextStripper(); firstPage.setStartPage(1); firstPage.setEndPage(1);
            var secondPage = new PDFTextStripper(); secondPage.setStartPage(2); secondPage.setEndPage(2);
            assertThat(firstPage.getText(pdf)).contains("Empresa Ejemplo", "Calle Mayor 1");
            assertThat(secondPage.getText(pdf)).doesNotContain("Empresa Ejemplo", "Calle Mayor 1");
            String output = System.getProperty("warehouse.transfer.export.artifacts");
            if (output != null) {
                Files.createDirectories(Path.of(output));
                var renderer = new PDFRenderer(pdf);
                javax.imageio.ImageIO.write(renderer.renderImageWithDPI(0, 110), "png", Path.of(output, "traspasos-page-1.png").toFile());
                javax.imageio.ImageIO.write(renderer.renderImageWithDPI(pdf.getNumberOfPages() - 1, 110), "png", Path.of(output, "traspasos-last-page.png").toFile());
            }
        }
        saveArtifact("traspasos.pdf", bytes);
    }

    @Test void refusesIncompletePaginationInsteadOfExportingPartialData() {
        when(transfers.listFiltered(anyInt(), anyInt(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PagedResult<>(List.of(), "1", true));
        assertThatThrownBy(() -> service.export("xlsx", filters)).isInstanceOf(IllegalStateException.class);
    }

    @Test void validatesFormatAndKeepsTransferPermissions() {
        assertThatThrownBy(() -> service.export("csv", filters)).isInstanceOf(IllegalArgumentException.class);
        assertThat(WarehouseTransferListExportController.class.getAnnotation(PreAuthorize.class).value())
                .contains("GESTION_ALMACEN", "STOCK_TRANSFER").doesNotContain("VENTA'");
        verifyNoInteractions(transfers);
    }

    private static void saveArtifact(String name, byte[] bytes) throws Exception {
        String output = System.getProperty("warehouse.transfer.export.artifacts", "target/warehouse-design");
        Files.createDirectories(Path.of(output));
        Files.write(Path.of(output, name), bytes);
    }
}
