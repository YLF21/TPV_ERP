package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.InvoicePresentationSnapshot;
import com.tpverp.backend.document.Voucher;
import com.tpverp.backend.document.VoucherPresentationSnapshot;
import com.tpverp.backend.document.VoucherPresentationSnapshotFactory;
import com.tpverp.backend.document.VoucherPrintService;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VoucherJasperRendererTest {

    @TempDir Path temporaryDirectory;

    @Test
    void rendersAmountCode128CompanyTraceabilityAndObservations() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var address = Map.of(
                "linea1", "Calle Empresa 1",
                "codigoPostal", "35001",
                "ciudad", "Las Palmas",
                "provincia", "Las Palmas",
                "pais", "ES",
                "telefono", "928000000",
                "email", "empresa@example.test");
        var company = new Company("B12345678", "TPV ERP SL", address);
        var store = new Store(company, "001", "Tienda Centro", address,
                "address-hash", "Atlantic/Canary", "EUR", "es");
        var template = DocumentTemplate.storeDraft(
                store, DocumentTemplateType.VALE, DocumentTemplateFormat.TICKET_80,
                "VALE_TICKET_80", 1, "Vale Ticket 80", UUID.randomUUID(),
                Instant.parse("2026-08-14T10:00:00Z"));
        byte[] source = Files.readAllBytes(Path.of(
                "..", "plantillas documentos", "VALE_TICKET_80.jrxml"));
        String sourceText = new String(source, StandardCharsets.UTF_8);
        assertThat(sourceText)
                .contains("uuid=\"fa494436-b2b4-4480-9222-1427f0a82a2f\" positionType=\"Float\"")
                .contains("fontSize=\"7.5\" bold=\"true\" textAdjust=\"StretchHeight\"")
                .doesNotContain("fontSize=\"7.5\" bold=\"true\" textAdjust=\"ScaleFont\"");
        var compiler = new SafeJrxmlCompiler();
        var compiled = compiler.compile(source);
        var storage = new DocumentTemplateArtifactStorage(temporaryDirectory);
        storage.write(template.getId(), compiled.source(), compiled.compiled());
        template.validateArtifact(
                SafeJrxmlCompiler.DATA_SCHEMA_VERSION, template.getId().toString(),
                compiled.sha256(), Instant.parse("2026-08-14T10:01:00Z"));
        template.activate(Instant.parse("2026-08-14T10:02:00Z"));
        var templates = mock(DocumentTemplateRepository.class);
        when(templates.findPrintableTemplate(template.getId(), company.getId(), store.getId()))
                .thenReturn(Optional.of(template));

        var voucher = new Voucher(
                store.getId(), "VABC12345678", new BigDecimal("25.50"),
                List.of("T-2026-17"), Instant.parse("2026-08-14T11:30:00Z"));
        voucher.capturePrintSnapshot("{}");
        var snapshot = new VoucherPresentationSnapshot(
                1, "Presentar el vale original.",
                new InvoicePresentationSnapshot.TemplateReference(
                        template.getId(), template.getCode(), template.getTemplateVersion(),
                        SafeJrxmlCompiler.DATA_SCHEMA_VERSION, compiled.sha256(), false),
                null, "TPV-01",
                List.of(
                        new VoucherPresentationSnapshot.TraceEntry(
                                "R-2026-000184", CommercialDocumentType.RECTIFICATIVA_VENTA,
                                LocalDate.of(2026, 8, 1), "EMISION"),
                        new VoucherPresentationSnapshot.TraceEntry(
                                "T-2026-004587", CommercialDocumentType.TICKET,
                                LocalDate.of(2026, 8, 14), "CONSUMO_PARCIAL"),
                        new VoucherPresentationSnapshot.TraceEntry(
                                "T-2026-004231", CommercialDocumentType.TICKET,
                                LocalDate.of(2026, 8, 7), "CONSUMO_PARCIAL"),
                        new VoucherPresentationSnapshot.TraceEntry(
                                "T-2026-003900", CommercialDocumentType.TICKET,
                                LocalDate.of(2026, 7, 25), "CONSUMO_PARCIAL")));
        var snapshotFactory = mock(VoucherPresentationSnapshotFactory.class);
        when(snapshotFactory.read("{}")).thenReturn(snapshot);
        when(snapshotFactory.logoDataUri(snapshot, store.getId())).thenReturn(
                null,
                "data:image/png;base64,"
                        + "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        var organization = mock(CurrentOrganization.class);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(company);
        var renderer = new InvoiceJasperRenderer(
                templates, storage, compiler, mapper,
                new BuiltInDocumentJrxmlCatalog(compiler));
        var service = new VoucherPrintService(
                organization, snapshotFactory, renderer, mapper);

        var rendered = service.render(voucher);
        var renderedWithLogo = service.render(voucher);

        byte[] pdf = java.util.Base64.getDecoder().decode(rendered.renderedPdf().base64());
        try (var document = Loader.loadPDF(pdf)) {
            assertThat(document.getPage(0).getMediaBox().getWidth()).isEqualTo(227f);
            String text = new PDFTextStripper().getText(document);
            assertThat(text)
                    .contains("VALE", "25,50 EUR", "VABC12345678",
                            "T-2026-004587", "T-2026-004231", "R-2026-000184",
                            "T-2026-003900",
                            "Tienda Centro", "TPV ERP SL",
                            "B12345678", "Calle Empresa 1", "928000000",
                            "Presentar el vale original")
                    .doesNotContain("empresa@example.test", "Consumo parcial",
                            "Factura rectificativa", "01/08/2026", "07/08/2026");
            assertThat(text.indexOf("T-2026-004587"))
                    .isLessThan(text.indexOf("T-2026-004231"));
            assertThat(text.indexOf("T-2026-004231"))
                    .isLessThan(text.indexOf("R-2026-000184"));
            assertThat(text.indexOf("R-2026-000184"))
                    .isLessThan(text.indexOf("T-2026-003900"));
        }
        var raster = ImageIO.read(new ByteArrayInputStream(
                java.util.Base64.getDecoder().decode(
                        rendered.ticketRenderedImage().base64())));
        assertThat(raster.getWidth()).isEqualTo(576);
        assertThat(raster.getHeight()).isBetween(100, 5_000);
        byte[] pdfWithLogo = java.util.Base64.getDecoder().decode(
                renderedWithLogo.renderedPdf().base64());
        try (var document = Loader.loadPDF(pdfWithLogo)) {
            assertThat(new PDFTextStripper().getText(document))
                    .contains("TPV ERP SL", "B12345678", "Calle Empresa 1", "928000000")
                    .doesNotContain("empresa@example.test");
        }
    }
}
