package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentRepository;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.DocumentLineCommand;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentExcelExportServiceTest {

    @Mock private CommercialDocumentRepository documents;
    @Mock private CurrentOrganization organization;

    @Test
    void exportsVisibleDocumentDataWithoutInternalIds() throws Exception {
        var document = document(CommercialDocumentType.TICKET);
        var company = new Company("B12345678", "EMPRESA PRUEBAS SL", address());
        var store = new Store(
                company, "001", "TIENDA PRUEBAS 001", address(), "address-hash",
                "Europe/Madrid", "EUR", "es-ES");
        when(documents.findById(document.getId())).thenReturn(Optional.of(document));
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(company);
        var service = new DocumentExcelExportService(documents, organization);

        var bytes = service.export(document.getId());

        try (var workbook = WorkbookFactory.create(new java.io.ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(1).getStringCellValue())
                    .isEqualTo("EMPRESA PRUEBAS SL");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("B12345678");
            assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("001");
            assertThat(sheet.getRow(3).getCell(1).getStringCellValue())
                    .isEqualTo("TIENDA PRUEBAS 001");
            assertThat(sheet.getRow(4).getCell(1).getStringCellValue()).isEqualTo("EUR");
            assertThat(sheet.getRow(0).getCell(4).getStringCellValue()).isEqualTo("TICKET");
            assertThat(sheet.getRow(1).getCell(4).getStringCellValue()).isEqualTo("001-260702-000001");
            assertThat(sheet.getRow(6).getCell(0).getStringCellValue()).isEqualTo("Código");
            assertThat(sheet.getRow(7).getCell(0).getStringCellValue()).isEqualTo("P001");
            assertThat(sheet.getRow(7).getCell(1).getStringCellValue()).isEqualTo("Producto");
            assertThat(sheet.getRow(7).getCell(3).getNumericCellValue()).isEqualTo(10.00);
            assertThat(sheet.getRow(7).getCell(3).getCellStyle().getDataFormatString()).contains("€");
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
            assertThat(sheet.getRow(0).getCell(0).getCellStyle().getBorderRight())
                    .isEqualTo(BorderStyle.THIN);
            assertThat(sheet.getRow(6).getCell(8).getCellStyle().getBorderBottom())
                    .isEqualTo(BorderStyle.THIN);
            assertThat(sheet.getRow(9).getCell(0).getCellStyle().getFillPattern())
                    .isEqualTo(FillPatternType.NO_FILL);
            assertThat(sheet.getRow(9).getCell(0).getCellStyle().getBorderLeft())
                    .isEqualTo(BorderStyle.THIN);
            assertThat(sheet.getRow(10).getCell(0).getCellStyle().getBorderLeft())
                    .isEqualTo(BorderStyle.THIN);
            assertThat(sheet.getRow(11).getCell(0).getCellStyle().getBorderLeft())
                    .isEqualTo(BorderStyle.THIN);
            assertThat(sheet.getPaneInformation()).isNotNull();
        }
        assertThat(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain(document.getId().toString());
    }

    private static java.util.Map<String, String> address() {
        return java.util.Map.of(
                "linea1", "Calle Pruebas 1",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES");
    }

    private static CommercialDocument document(CommercialDocumentType type) {
        var document = new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), type,
                LocalDate.of(2026, 7, 2), UUID.randomUUID(), BigDecimal.ZERO);
        document.addLine(new DocumentLineCommand(
                UUID.randomUUID(), 2, "P001", "Producto", "VENTA",
                new BigDecimal("10.00"), BigDecimal.ZERO, true, "IVA",
                new BigDecimal("21")).toEntity(document, 1));
        document.confirm("001-260702-000001", UUID.randomUUID(), Instant.now(), true);
        return document;
    }
}
