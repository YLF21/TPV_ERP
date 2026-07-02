package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentRepository;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.DocumentLineCommand;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentExcelExportServiceTest {

    @Mock private CommercialDocumentRepository documents;

    @Test
    void exportsVisibleDocumentDataWithoutInternalIds() throws Exception {
        var document = document();
        when(documents.findById(document.getId())).thenReturn(Optional.of(document));
        var service = new DocumentExcelExportService(documents);

        var bytes = service.export(document.getId());

        try (var workbook = WorkbookFactory.create(new java.io.ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("TICKET");
            assertThat(sheet.getRow(5).getCell(0).getStringCellValue()).isEqualTo("P001");
            assertThat(sheet.getRow(5).getCell(1).getStringCellValue()).isEqualTo("Producto");
        }
        assertThat(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain(document.getId().toString());
    }

    private static CommercialDocument document() {
        var document = new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 7, 2), UUID.randomUUID(), BigDecimal.ZERO);
        document.addLine(new DocumentLineCommand(
                UUID.randomUUID(), 2, "P001", "Producto", "VENTA",
                new BigDecimal("10.00"), BigDecimal.ZERO, true, "IVA",
                new BigDecimal("21")).toEntity(document, 1));
        document.confirm("001-260702-000001", UUID.randomUUID(), Instant.now(), true);
        return document;
    }
}
