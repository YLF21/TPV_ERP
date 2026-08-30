package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.backend.document.TicketCancellationService;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class TicketCancellationJasperRendererTest {

    @Test
    void rendersTheCancellationReceiptAsTicketPdfAndRaster() throws Exception {
        var address = Map.of(
                "linea1", "Calle Empresa 1",
                "codigoPostal", "35001",
                "ciudad", "Las Palmas",
                "provincia", "Las Palmas",
                "pais", "ES");
        var company = new Company("B12345678", "TPV ERP, S.L.", address);
        var store = new Store(company, "001", "Tienda Centro", address,
                "address-hash", "Atlantic/Canary", "EUR", "es-ES");
        var receipt = new TicketCancellationService.CancellationReceipt(
                UUID.fromString("27c125ae-b1fb-47c4-b8fb-a49c0e3be6e1"),
                "T-2026-008174",
                Instant.parse("2026-08-16T12:30:00Z"),
                Instant.parse("2026-08-16T13:15:00Z"),
                new BigDecimal("25.50"),
                "Error de cobro",
                "CAJERO",
                "ADMIN",
                true,
                List.of(new TicketCancellationService.CancellationReceiptPayment(
                        "credito_devolucion", new BigDecimal("25.50"), "DEV-123")));

        var rendered = new TicketCancellationJasperRenderer(
                new SafeJrxmlCompiler()).render(receipt, store);

        assertThat(rendered.pdf()).startsWith("%PDF".getBytes());
        try (var document = Loader.loadPDF(rendered.pdf())) {
            assertThat(document.getNumberOfPages()).isOne();
            assertThat(document.getPage(0).getMediaBox().getWidth()).isEqualTo(227f);
            assertThat(new PDFTextStripper().getText(document))
                    .contains(
                            "Tienda Centro",
                            "TPV ERP, S.L.",
                            "B12345678",
                            "COMPROBANTE DE",
                            "ANULACI",
                            "DOCUMENTO NO FISCAL",
                            "T-2026-008174",
                            "Error de cobro",
                            "CAJERO",
                            "ADMIN",
                            "CREDITO DEVOLUCION",
                            "DEV-123",
                            "TOTAL ANULADO",
                            "25.50 EUR");
        }
        var raster = ImageIO.read(new ByteArrayInputStream(rendered.png()));
        assertThat(raster).isNotNull();
        assertThat(raster.getWidth()).isEqualTo(576);
        assertThat(raster.getHeight()).isBetween(100, 5_000);
    }
}
