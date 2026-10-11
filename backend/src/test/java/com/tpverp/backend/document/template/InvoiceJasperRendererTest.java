package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentRepository;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.DocumentAttributionResolver;
import com.tpverp.backend.document.DocumentLine;
import com.tpverp.backend.document.DocumentLineCommand;
import com.tpverp.backend.document.DocumentLineType;
import com.tpverp.backend.document.DocumentPayment;
import com.tpverp.backend.document.FiscalPrintView;
import com.tpverp.backend.document.InvoiceFiscalProfile;
import com.tpverp.backend.document.InvoicePresentationSnapshot;
import com.tpverp.backend.document.MemberDiscountDocumentTestFixture;
import com.tpverp.backend.document.PaymentMethod;
import com.tpverp.backend.document.SalesInvoiceRectification;
import com.tpverp.backend.document.SalesInvoiceRectificationReason;
import com.tpverp.backend.document.SalesInvoiceRectificationRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.party.Customer;
import com.tpverp.backend.party.CustomerRate;
import com.tpverp.backend.party.DocumentType;
import com.tpverp.backend.party.FiscalAddress;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserAccountRepository;
import com.tpverp.backend.terminal.Terminal;
import com.tpverp.backend.terminal.TerminalRepository;
import com.tpverp.backend.verifactu.FiscalEndpointEnvironment;
import com.tpverp.backend.verifactu.FiscalMode;
import com.tpverp.backend.verifactu.FiscalPrintSnapshotFactory;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.json.query.JsonQueryExecuterFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InvoiceJasperRendererTest {

    private static final String LAYOUT_OBSERVATIONS = """
            OBS-LINE-1: texto de comprobación de separación
            OBS-LINE-2: texto de comprobación de separación
            OBS-LINE-3: texto de comprobación de separación
            OBS-LINE-4: fin de observaciones""";

    @TempDir Path temporaryDirectory;

    @Test
    void formatsPaymentMethodIdentifiersInStructuredInvoiceData() {
        var fixture = fixture();
        var method = new PaymentMethod(
                fixture.company().getId(), "credito_devolucion", true);
        fixture.document().addPayment(new DocumentPayment(
                fixture.document(), method, 1, new BigDecimal("12.10"), true,
                null, null, Instant.parse("2026-08-10T09:01:00Z")));
        var compiler = new SafeJrxmlCompiler();
        var renderer = new InvoiceJasperRenderer(
                mock(DocumentTemplateRepository.class),
                new DocumentTemplateArtifactStorage(temporaryDirectory),
                compiler, new ObjectMapper(), new BuiltInDocumentJrxmlCatalog(compiler));

        var json = renderer.data(
                fixture.document(), fixture.store(), fixture.company(), fixture.customer(),
                snapshot(fixture.template(), "a".repeat(64)), null);

        assertThat(method.getNombre()).isEqualTo("CREDITO_DEVOLUCION");
        assertThat(json.at("/payment/method").asText()).isEqualTo("CREDITO DEVOLUCION");
    }

    @Test
    void rendersFrozenLineBarcodeFromJsonqlIntoPdf() throws Exception {
        var fixture = fixture();
        var compiler = new SafeJrxmlCompiler();
        var compiled = compiler.compile(jrxml().getBytes(StandardCharsets.UTF_8));
        var storage = new DocumentTemplateArtifactStorage(temporaryDirectory);
        storage.write(fixture.template().getId(), compiled.source(), compiled.compiled());
        fixture.template().validateArtifact(
                SafeJrxmlCompiler.DATA_SCHEMA_VERSION,
                fixture.template().getId().toString(),
                compiled.sha256(),
                Instant.parse("2026-08-10T10:00:00Z"));
        fixture.template().activate(Instant.parse("2026-08-10T10:01:00Z"));
        var templates = mock(DocumentTemplateRepository.class);
        when(templates.findPrintableTemplate(
                fixture.template().getId(), fixture.company().getId(), fixture.store().getId()))
                .thenReturn(Optional.of(fixture.template()));
        var renderer = new InvoiceJasperRenderer(
                templates, storage, compiler, new ObjectMapper(),
                new BuiltInDocumentJrxmlCatalog(compiler));

        var pdf = renderer.render(
                fixture.document(), fixture.store(), fixture.company(), fixture.customer(),
                snapshot(fixture.template(), compiled.sha256()), null).orElseThrow();

        assertThat(pdf).startsWith(0x25, 0x50, 0x44, 0x46);
        try (var document = Loader.loadPDF(pdf)) {
            assertThat(new PDFTextStripper().getText(document))
                    .contains("8430000000010");
        }
    }

    @Test
    void rendersManuallySuppliedEightyMillimeterInvoiceWithFiscalQr() throws Exception {
        var fixture = fixture();
        var template = DocumentTemplate.storeDraft(
                fixture.store(), DocumentTemplateType.FACTURA_VENTA,
                DocumentTemplateFormat.TICKET_80, "FACTURA_TICKET_80", 1,
                "Factura ticket 80", UUID.randomUUID(),
                Instant.parse("2026-08-10T08:00:00Z"));
        var compiler = new SafeJrxmlCompiler();
        byte[] source = Files.readAllBytes(Path.of(
                "..", "plantillas documentos", "FACTURA_VENTA_TICKET_80.jrxml"));
        var compiled = compiler.compile(source);
        var storage = new DocumentTemplateArtifactStorage(temporaryDirectory);
        storage.write(template.getId(), compiled.source(), compiled.compiled());
        template.validateArtifact(
                SafeJrxmlCompiler.DATA_SCHEMA_VERSION,
                template.getId().toString(), compiled.sha256(),
                Instant.parse("2026-08-10T10:00:00Z"));
        template.activate(Instant.parse("2026-08-10T10:01:00Z"));
        var templates = mock(DocumentTemplateRepository.class);
        when(templates.findPrintableTemplate(
                template.getId(), fixture.company().getId(), fixture.store().getId()))
                .thenReturn(Optional.of(template));
        var renderer = new InvoiceJasperRenderer(
                templates, storage, compiler, new ObjectMapper(),
                new BuiltInDocumentJrxmlCatalog(compiler));
        var ticketReference = new InvoicePresentationSnapshot.TemplateReference(
                template.getId(), template.getCode(), template.getTemplateVersion(),
                SafeJrxmlCompiler.DATA_SCHEMA_VERSION, compiled.sha256(), false);
        var presentation = new InvoicePresentationSnapshot(
                4, InvoiceFiscalProfile.IVA, LAYOUT_OBSERVATIONS, List.of(),
                snapshot(fixture.template(), "a".repeat(64)).template(),
                ticketReference, null);

        var rendered = renderer.renderDocument(
                fixture.document(), fixture.store(), fixture.company(), fixture.customer(),
                presentation, "https://www2.agenciatributaria.gob.es/qr",
                null, DocumentTemplateFormat.TICKET_80).orElseThrow();
        var pdf = rendered.pdf();
        try (var document = Loader.loadPDF(pdf)) {
            assertThat(document.getPage(0).getMediaBox().getWidth()).isEqualTo(227f);
            assertThat(new PDFTextStripper().getText(document))
                    .contains("FACTURA", "FV-2026-1", "Cód. cliente", "C-001", "8430000000010",
                            "TOTAL FACTURA", "OBS-LINE-4", "QR tributario:");
            var observations = textBounds(document, "OBS-LINE-4");
            var qrPrefix = textBounds(document, "QR tributario:");
            assertThat(qrPrefix.top()).isGreaterThan(observations.bottom() + 2f);
        }
        var raster = ImageIO.read(new ByteArrayInputStream(rendered.ticketRasterPng()));
        assertThat(raster.getWidth()).isEqualTo(576);
        assertThat(raster.getHeight()).isBetween(100, 5_073);
        assertThat(decodeQr(raster)).isEqualTo("https://www2.agenciatributaria.gob.es/qr");
    }

    @Test
    void ticketFooterUsesConfirmedOperatorAndOriginTerminalInStore() throws Exception {
        var fixture = fixture();
        var terminalId = UUID.randomUUID();
        fixture.document().assignOriginTerminal(terminalId);
        var users = mock(UserAccountRepository.class);
        var terminals = mock(TerminalRepository.class);
        var operator = mock(UserAccount.class);
        var terminal = mock(Terminal.class);
        when(operator.getId()).thenReturn(fixture.document().getConfirmadoPor());
        when(operator.getUserName()).thenReturn("OPERADOR PERSISTIDO");
        when(operator.getTienda()).thenReturn(fixture.store());
        when(terminal.getId()).thenReturn(terminalId);
        when(terminal.getNombre()).thenReturn("CAJA 04");
        when(terminal.getTienda()).thenReturn(fixture.store());
        when(users.findAllById(any())).thenReturn(List.of(operator));
        when(terminals.findAllById(any())).thenReturn(List.of(terminal));
        var compiler = new SafeJrxmlCompiler();
        var builtIns = new BuiltInDocumentJrxmlCatalog(compiler);
        var renderer = new InvoiceJasperRenderer(
                mock(DocumentTemplateRepository.class),
                new DocumentTemplateArtifactStorage(temporaryDirectory),
                compiler, new ObjectMapper(), builtIns);
        renderer.setPrintAttributionResolver(new DocumentAttributionResolver(users, terminals));
        var presentation = new InvoicePresentationSnapshot(
                4, InvoiceFiscalProfile.IGIC_MINORISTA, LAYOUT_OBSERVATIONS, List.of(),
                builtIns.reference(DocumentTemplateType.FACTURA_VENTA,
                        DocumentTemplateFormat.A4),
                builtIns.reference(DocumentTemplateType.FACTURA_VENTA,
                        DocumentTemplateFormat.TICKET_80), null);

        var json = renderer.data(fixture.document(), fixture.store(),
                fixture.company(), fixture.customer(), presentation, null);
        assertThat(json.at("/operatorName").asText()).isEqualTo("OPERADOR PERSISTIDO");
        assertThat(json.at("/terminalName").asText()).isEqualTo("CAJA 04");
        verify(users).findAllById(any());
        verify(terminals).findAllById(any());

        var rendered = renderer.renderDocument(
                fixture.document(), fixture.store(), fixture.company(), fixture.customer(),
                presentation, null, null, DocumentTemplateFormat.TICKET_80).orElseThrow();
        var previewDirectory = System.getProperty("tpv.test.printPreviewDirectory");
        if (previewDirectory != null && !previewDirectory.isBlank()) {
            var directory = Files.createDirectories(Path.of(previewDirectory));
            Files.write(directory.resolve("FACTURA_VENTA_TICKET_80-footer-IGIC_MINORISTA.pdf"),
                    rendered.pdf());
        }
        try (var pdf = Loader.loadPDF(rendered.pdf())) {
            var text = new PDFTextStripper().getText(pdf);
            assertThat(text).contains("ATENDIDO POR: OPERADOR PERSISTIDO",
                    "TERMINAL: CAJA 04", "COMERCIANTE MINORISTA", "OBSERVACIONES");
            assertThat(textBounds(pdf, "TERMINAL:").top())
                    .isGreaterThan(textBounds(pdf, "ATENDIDO POR:").bottom());
            assertThat(textBounds(pdf, "COMERCIANTE MINORISTA").top())
                    .isGreaterThan(textBounds(pdf, "TERMINAL:").bottom());
            assertThat(textBounds(pdf, "OBSERVACIONES").top())
                    .isGreaterThan(textBounds(pdf, "COMERCIANTE MINORISTA").bottom());
        }
    }

    @Test
    void ticketFooterOmitsUnresolvedAttributionFromAnotherStore() {
        var fixture = fixture();
        var terminalId = UUID.randomUUID();
        fixture.document().assignOriginTerminal(terminalId);
        var users = mock(UserAccountRepository.class);
        var terminals = mock(TerminalRepository.class);
        var operator = mock(UserAccount.class);
        var terminal = mock(Terminal.class);
        var otherStore = new Store(fixture.company(), "002", "Otra tienda",
                fixture.company().getDomicilioFiscal(), UUID.randomUUID().toString(),
                "Atlantic/Canary", "EUR", "es-ES");
        when(operator.getId()).thenReturn(fixture.document().getConfirmadoPor());
        when(operator.getUserName()).thenReturn("OPERADOR AJENO");
        when(operator.getTienda()).thenReturn(otherStore);
        when(terminal.getId()).thenReturn(terminalId);
        when(terminal.getNombre()).thenReturn("TERMINAL AJENO");
        when(terminal.getTienda()).thenReturn(otherStore);
        when(users.findAllById(any())).thenReturn(List.of(operator));
        when(terminals.findAllById(any())).thenReturn(List.of(terminal));
        var renderer = new InvoiceJasperRenderer(
                mock(DocumentTemplateRepository.class),
                new DocumentTemplateArtifactStorage(temporaryDirectory),
                new SafeJrxmlCompiler(), new ObjectMapper(),
                new BuiltInDocumentJrxmlCatalog(new SafeJrxmlCompiler()));
        renderer.setPrintAttributionResolver(new DocumentAttributionResolver(users, terminals));

        var json = renderer.data(fixture.document(), fixture.store(),
                fixture.company(), fixture.customer(),
                snapshot(fixture.template(), "a".repeat(64)), null);

        assertThat(json.at("/operatorName").isNull()).isTrue();
        assertThat(json.at("/terminalName").isNull()).isTrue();
        verify(users).findAllById(any());
        verify(terminals).findAllById(any());
    }

    @Test
    void ticketFooterAcceptsPersistedGlobalAdminAttribution() {
        var fixture = fixture();
        var users = mock(UserAccountRepository.class);
        var terminals = mock(TerminalRepository.class);
        var admin = mock(UserAccount.class);
        when(admin.getId()).thenReturn(fixture.document().getConfirmadoPor());
        when(admin.getUserName()).thenReturn("ADMIN");
        when(users.findAllById(any())).thenReturn(List.of(admin));
        var compiler = new SafeJrxmlCompiler();
        var renderer = new InvoiceJasperRenderer(
                mock(DocumentTemplateRepository.class),
                new DocumentTemplateArtifactStorage(temporaryDirectory),
                compiler, new ObjectMapper(),
                new BuiltInDocumentJrxmlCatalog(compiler));
        renderer.setPrintAttributionResolver(new DocumentAttributionResolver(users, terminals));

        var json = renderer.data(fixture.document(), fixture.store(),
                fixture.company(), fixture.customer(),
                snapshot(fixture.template(), "a".repeat(64)), null);

        assertThat(json.at("/operatorName").asText()).isEqualTo("ADMIN");
        assertThat(json.at("/terminalName").isNull()).isTrue();
    }

    @Test
    void deliveryNoteAndRectificationTicketFootersKeepOperatorAndTerminalInOrder()
            throws Exception {
        var fixture = fixture();
        var compiler = new SafeJrxmlCompiler();
        var builtIns = new BuiltInDocumentJrxmlCatalog(compiler);
        var users = mock(UserAccountRepository.class);
        var terminals = mock(TerminalRepository.class);
        var operator = mock(UserAccount.class);
        var terminal = mock(Terminal.class);
        when(operator.getUserName()).thenReturn("OPERADOR PERSISTIDO");
        when(operator.getTienda()).thenReturn(fixture.store());
        when(terminal.getNombre()).thenReturn("CAJA 04");
        when(terminal.getTienda()).thenReturn(fixture.store());
        when(users.findAllById(any())).thenReturn(List.of(operator));
        when(terminals.findAllById(any())).thenReturn(List.of(terminal));
        var renderer = new InvoiceJasperRenderer(
                mock(DocumentTemplateRepository.class),
                new DocumentTemplateArtifactStorage(temporaryDirectory),
                compiler, new ObjectMapper(), builtIns);
        renderer.setPrintAttributionResolver(new DocumentAttributionResolver(users, terminals));

        for (var type : List.of(
                CommercialDocumentType.ALBARAN_VENTA,
                CommercialDocumentType.RECTIFICATIVA_VENTA)) {
            var document = new CommercialDocument(
                    fixture.store().getId(), UUID.randomUUID(), type,
                    LocalDate.of(2026, 9, 18), UUID.randomUUID(), BigDecimal.ZERO);
            var terminalId = UUID.randomUUID();
            document.assignOriginTerminal(terminalId);
            document.addLine(new DocumentLine(
                    document, UUID.randomUUID(), 1,
                    type == CommercialDocumentType.RECTIFICATIVA_VENTA
                            ? BigDecimal.ONE.negate() : BigDecimal.ONE,
                    "P-001", "Producto", "VENTA", new BigDecimal("10.00"),
                    BigDecimal.ZERO, true, "IGIC", new BigDecimal("7.00")));
            document.confirm(type.name() + "-2026-1", UUID.randomUUID(),
                    Instant.parse("2026-09-18T10:01:00Z"), false);
            when(operator.getId()).thenReturn(document.getConfirmadoPor());
            when(terminal.getId()).thenReturn(terminalId);
            var templateType = type == CommercialDocumentType.ALBARAN_VENTA
                    ? DocumentTemplateType.ALBARAN_VENTA
                    : DocumentTemplateType.RECTIFICATIVA_VENTA;
            var presentation = new InvoicePresentationSnapshot(
                    4, InvoiceFiscalProfile.IGIC_MINORISTA, null, List.of(),
                    builtIns.reference(templateType, DocumentTemplateFormat.A4),
                    builtIns.reference(templateType, DocumentTemplateFormat.TICKET_80),
                    null);
            if (type == CommercialDocumentType.RECTIFICATIVA_VENTA) {
                var documents = mock(CommercialDocumentRepository.class);
                var rectifications = mock(SalesInvoiceRectificationRepository.class);
                var metadata = new SalesInvoiceRectification(
                        document.getId(), fixture.document().getId(),
                        SalesInvoiceRectificationReason.POST_SALE_DISCOUNT,
                        "Descuento posterior a la venta",
                        Instant.parse("2026-09-18T10:00:00Z"));
                when(rectifications.findByDocumentId(document.getId()))
                        .thenReturn(Optional.of(metadata));
                when(documents.findByIdAndTiendaId(
                        fixture.document().getId(), fixture.store().getId()))
                        .thenReturn(Optional.of(fixture.document()));
                renderer.setRectificationContext(documents, rectifications);
            }

            var rendered = renderer.renderDocument(
                    document, fixture.store(), fixture.company(),
                    type == CommercialDocumentType.RECTIFICATIVA_VENTA
                            ? fixture.customer() : null,
                    presentation, null, null, DocumentTemplateFormat.TICKET_80)
                    .orElseThrow();
            var previewDirectory = System.getProperty("tpv.test.printPreviewDirectory");
            if (previewDirectory != null && !previewDirectory.isBlank()) {
                var directory = Files.createDirectories(Path.of(previewDirectory));
                Files.write(directory.resolve(type.name() + "-footer-IGIC_MINORISTA.pdf"),
                        rendered.pdf());
            }
            try (var pdf = Loader.loadPDF(rendered.pdf())) {
                var text = new PDFTextStripper().getText(pdf);
                assertThat(text).contains("ATENDIDO POR: OPERADOR PERSISTIDO",
                        "TERMINAL: CAJA 04")
                        .doesNotContain("COMERCIANTE MINORISTA", "OBSERVACIONES");
                assertThat(textBounds(pdf, "TERMINAL:").top())
                        .isGreaterThan(textBounds(pdf, "ATENDIDO POR:").bottom());
                if (type == CommercialDocumentType.RECTIFICATIVA_VENTA) {
                    assertThat(text).contains("Factura original: FV-2026-1",
                            "Causa: POST_SALE_DISCOUNT");
                }
            }
        }
    }

    @Test
    void ticketInvoicePrintsThePersistedMemberPercentageInsteadOfTheTechnicalLineName()
            throws Exception {
        var fixture = fixture();
        var document = new CommercialDocument(
                fixture.store().getId(), UUID.randomUUID(),
                CommercialDocumentType.FACTURA_VENTA,
                LocalDate.of(2026, 9, 18), UUID.randomUUID(), BigDecimal.ZERO);
        document.addLine(new DocumentLine(
                document, UUID.randomUUID(), 1, BigDecimal.ONE,
                "P-MIEMBRO", "Producto miembro", "VENTA", new BigDecimal("100.00"),
                BigDecimal.ZERO, true, "IVA", new BigDecimal("21.00")));
        MemberDiscountDocumentTestFixture.apply(document, new BigDecimal("7.50"));
        document.confirm("FV-2026-MIEMBRO", UUID.randomUUID(),
                Instant.parse("2026-09-18T10:01:00Z"), false);
        var compiler = new SafeJrxmlCompiler();
        var builtIns = new BuiltInDocumentJrxmlCatalog(compiler);
        var renderer = new InvoiceJasperRenderer(
                mock(DocumentTemplateRepository.class),
                new DocumentTemplateArtifactStorage(temporaryDirectory),
                compiler, new ObjectMapper(), builtIns);
        var presentation = new InvoicePresentationSnapshot(
                4, InvoiceFiscalProfile.IVA, null, List.of(),
                builtIns.reference(DocumentTemplateType.FACTURA_VENTA,
                        DocumentTemplateFormat.A4),
                builtIns.reference(DocumentTemplateType.FACTURA_VENTA,
                        DocumentTemplateFormat.TICKET_80),
                null);

        var rendered = renderer.renderDocument(
                document, fixture.store(), fixture.company(), fixture.customer(),
                presentation, null, null, DocumentTemplateFormat.TICKET_80)
                .orElseThrow();

        try (var pdf = Loader.loadPDF(rendered.pdf())) {
            assertThat(new PDFTextStripper().getText(pdf))
                    .contains("Descuento miembro 7.5%")
                    .doesNotContain("DESCUENTO DOCUMENTAL");
        }
    }

    @Test
    void ticketRectificationKeepsTheFiscalQrBlockBelowTheTotal() throws Exception {
        var fixture = fixture();
        var document = new CommercialDocument(
                fixture.store().getId(), UUID.randomUUID(),
                CommercialDocumentType.RECTIFICATIVA_VENTA,
                LocalDate.of(2026, 9, 18), UUID.randomUUID(), BigDecimal.ZERO);
        document.addLine(new DocumentLine(
                document, UUID.randomUUID(), 1, BigDecimal.ONE.negate(),
                "P-RECT", "Producto rectificado", "VENTA", new BigDecimal("10.00"),
                BigDecimal.ZERO, true, "IVA", new BigDecimal("21.00")));
        document.confirm("FRV-2026-1", UUID.randomUUID(),
                Instant.parse("2026-09-18T10:01:00Z"), false);
        var compiler = new SafeJrxmlCompiler();
        var builtIns = new BuiltInDocumentJrxmlCatalog(compiler);
        var renderer = new InvoiceJasperRenderer(
                mock(DocumentTemplateRepository.class),
                new DocumentTemplateArtifactStorage(temporaryDirectory),
                compiler, new ObjectMapper(), builtIns);
        var presentation = new InvoicePresentationSnapshot(
                4, InvoiceFiscalProfile.IVA, null, List.of(),
                builtIns.reference(DocumentTemplateType.RECTIFICATIVA_VENTA,
                        DocumentTemplateFormat.A4),
                builtIns.reference(DocumentTemplateType.RECTIFICATIVA_VENTA,
                        DocumentTemplateFormat.TICKET_80),
                null);

        assertThat(renderer.data(
                document, fixture.store(), fixture.company(), fixture.customer(),
                presentation, null).at("/lines/0/unitPriceSale").isMissingNode()).isTrue();

        var rendered = renderer.renderDocument(
                document, fixture.store(), fixture.company(), fixture.customer(),
                presentation, "https://www2.agenciatributaria.gob.es/qr-rectificativa",
                null, DocumentTemplateFormat.TICKET_80).orElseThrow();

        try (var pdf = Loader.loadPDF(rendered.pdf())) {
            assertThat(new PDFTextStripper().getText(pdf))
                    .contains("FACTURA RECTIFICATIVA", "TOTAL:", "QR tributario:");
            var total = textBounds(pdf, "TOTAL:");
            var qrPrefix = textBounds(pdf, "QR tributario:");
            assertThat(qrPrefix.top()).isGreaterThan(total.bottom() + 2f);
        }
    }

    @Test
    void rendersBackendLogoParameterAndMovesIssuerLeftWhenLogoIsAbsent() throws Exception {
        var fixture = fixture();
        var compiler = new SafeJrxmlCompiler();
        var compiled = compiler.compile(logoJrxml().getBytes(StandardCharsets.UTF_8));
        var storage = new DocumentTemplateArtifactStorage(temporaryDirectory);
        storage.write(fixture.template().getId(), compiled.source(), compiled.compiled());
        fixture.template().validateArtifact(
                SafeJrxmlCompiler.DATA_SCHEMA_VERSION,
                fixture.template().getId().toString(),
                compiled.sha256(),
                Instant.parse("2026-08-10T10:00:00Z"));
        fixture.template().activate(Instant.parse("2026-08-10T10:01:00Z"));
        var templates = mock(DocumentTemplateRepository.class);
        when(templates.findPrintableTemplate(
                fixture.template().getId(), fixture.company().getId(), fixture.store().getId()))
                .thenReturn(Optional.of(fixture.template()));
        var renderer = new InvoiceJasperRenderer(
                templates, storage, compiler, new ObjectMapper(),
                new BuiltInDocumentJrxmlCatalog(compiler));
        var presentation = snapshot(fixture.template(), compiled.sha256());

        var withLogo = renderer.render(
                fixture.document(), fixture.store(), fixture.company(), fixture.customer(),
                presentation, null, logoDataUri()).orElseThrow();
        var withoutLogo = renderer.render(
                fixture.document(), fixture.store(), fixture.company(), fixture.customer(),
                presentation, null, null).orElseThrow();

        int withLogoPixels;
        try (var pdf = Loader.loadPDF(withLogo)) {
            withLogoPixels = nonWhitePixelsInLogoArea(pdf);
            assertThat(textX(pdf, "TPV ERP SL")).isGreaterThan(80f);
        }
        int withoutLogoPixels;
        try (var pdf = Loader.loadPDF(withoutLogo)) {
            withoutLogoPixels = nonWhitePixelsInLogoArea(pdf);
            assertThat(textX(pdf, "TPV ERP SL")).isBetween(15f, 30f);
        }
        assertThat(withLogoPixels).isGreaterThan(withoutLogoPixels + 500);
    }

    @Test
    void builtInTemplateRendersPackagedJrxmlWithExpectedContent() throws Exception {
        var fixture = fixture();
        var builtIns = new BuiltInDocumentJrxmlCatalog(new SafeJrxmlCompiler());
        var renderer = new InvoiceJasperRenderer(
                mock(DocumentTemplateRepository.class),
                new DocumentTemplateArtifactStorage(temporaryDirectory),
                new SafeJrxmlCompiler(),
                new ObjectMapper(),
                builtIns);
        var snapshot = new InvoicePresentationSnapshot(
                4, InvoiceFiscalProfile.IVA, null, List.of(),
                builtIns.reference(DocumentTemplateType.FACTURA_VENTA,
                        DocumentTemplateFormat.A4));

        var qrUrl = "https://prewww2.aeat.es/wlpl/TIKE-CONT/ValidarQR"
                + "?nif=B12345678&numserie=FV-2026-1&fecha=10-08-2026&importe=10.00";
        var fiscal = new FiscalPrintView(
                FiscalPrintSnapshotFactory.FORMAT_VERSION,
                "TPV-ERP-2026.08.25",
                FiscalMode.VERIFACTU,
                FiscalEndpointEnvironment.TEST,
                qrUrl,
                "A".repeat(64),
                FiscalPrintSnapshotFactory.PREFIX,
                FiscalPrintSnapshotFactory.VERIFACTU_LEGEND,
                FiscalPrintSnapshotFactory.TEST_NOTICE,
                "Obligado congelado SL",
                "B12345678",
                Map.of(
                        "linea1", "Calle congelada 7",
                        "codigoPostal", "35007",
                        "ciudad", "Telde",
                        "provincia", "Las Palmas",
                        "pais", "ES"));
        var pdf = renderer.renderWithFiscalSnapshot(
                fixture.document(), fixture.store(), fixture.company(), fixture.customer(),
                snapshot, fiscal, null, DocumentTemplateFormat.A4).orElseThrow();

        assertThat(pdf).startsWith(0x25, 0x50, 0x44, 0x46);
        try (var rendered = Loader.loadPDF(pdf)) {
            assertThat(new PDFTextStripper().getText(rendered))
                    .contains("FACTURA", "FV-2026-1", "Obligado congelado SL",
                            "Calle congelada 7",
                            "QR tributario",
                            "Factura verificable en la sede electrónica de",
                            "la AEAT",
                            "ENTORNO DE PRUEBAS - SIN VALIDEZ")
                    .doesNotContain("TPV ERP SL", "Calle Emisor 1");
            assertThat(decodeQr(new PDFRenderer(rendered).renderImageWithDPI(0, 150)))
                    .isEqualTo(qrUrl);
        }
    }

    @Test
    void completeInvoiceTemplatesRespectTheFrozenMinoristaProfileInRenderedPdf()
            throws Exception {
        for (String filename : List.of(
                "FACTURA_VENTA_A4.jrxml", "FACTURA_VENTA_A4_v2.jrxml",
                "FACTURA_VENTA_TICKET_80.jrxml", "FACTURA_VENTA_TICKET_80_v2.jrxml")) {
            var fixture = fixture();
            var format = filename.contains("TICKET_80")
                    ? DocumentTemplateFormat.TICKET_80 : DocumentTemplateFormat.A4;
            var template = DocumentTemplate.storeDraft(
                    fixture.store(), DocumentTemplateType.FACTURA_VENTA, format,
                    "FACTURA_PERFIL", 1, "Factura perfil fiscal", UUID.randomUUID(),
                    Instant.parse("2026-08-10T08:00:00Z"));
            var compiler = new SafeJrxmlCompiler();
            var source = Files.readAllBytes(Path.of(
                    "..", "plantillas documentos", filename));
            var legacyVariant = filename.endsWith("_v2.jrxml");
            byte[] legacyCompiled = null;
            var storage = new DocumentTemplateArtifactStorage(temporaryDirectory);
            var templates = mock(DocumentTemplateRepository.class);
            var renderer = new InvoiceJasperRenderer(
                    templates, storage, compiler, new ObjectMapper(),
                    new BuiltInDocumentJrxmlCatalog(compiler));
            InvoicePresentationSnapshot.TemplateReference reference = null;
            if (legacyVariant) {
                try (var input = new ByteArrayInputStream(source);
                        var output = new ByteArrayOutputStream()) {
                    JasperCompileManager.getInstance(SafeJrxmlCompiler.secureContext())
                            .compileToStream(input, output);
                    legacyCompiled = output.toByteArray();
                }
            } else {
                var compiled = compiler.compile(source);
                storage.write(template.getId(), compiled.source(), compiled.compiled());
                template.validateArtifact(
                        SafeJrxmlCompiler.DATA_SCHEMA_VERSION, template.getId().toString(),
                        compiled.sha256(), Instant.parse("2026-08-10T10:00:00Z"));
                template.activate(Instant.parse("2026-08-10T10:01:00Z"));
                when(templates.findPrintableTemplate(
                        template.getId(), fixture.company().getId(), fixture.store().getId()))
                        .thenReturn(Optional.of(template));
                reference = new InvoicePresentationSnapshot.TemplateReference(
                        template.getId(), template.getCode(), template.getTemplateVersion(),
                        SafeJrxmlCompiler.DATA_SCHEMA_VERSION, compiled.sha256(), false);
            }

            for (var profile : InvoiceFiscalProfile.values()) {
                var document = profile == InvoiceFiscalProfile.IVA
                        ? fixture.document() : igicInvoice(fixture.store());
                var presentation = new InvoicePresentationSnapshot(
                        4, profile, null, List.of(),
                        format == DocumentTemplateFormat.A4 ? reference : null,
                        format == DocumentTemplateFormat.TICKET_80 ? reference : null,
                        null);
                var json = renderer.data(
                        document, fixture.store(), fixture.company(), fixture.customer(),
                        presentation, null);
                assertThat(json.at("/fiscalProfile").asText())
                        .as(filename + " " + profile).isEqualTo(profile.name());

                byte[] pdfBytes = legacyVariant
                        ? renderLegacyInvoiceTemplate(legacyCompiled, json)
                        : renderer.renderDocument(
                                document, fixture.store(), fixture.company(), fixture.customer(),
                                presentation, null, null, format).orElseThrow().pdf();
                var previewDirectory = System.getProperty("tpv.test.printPreviewDirectory");
                if (previewDirectory != null && !previewDirectory.isBlank()) {
                    var directory = Files.createDirectories(Path.of(previewDirectory));
                    Files.write(directory.resolve(filename.replace(".jrxml", "-")
                            + profile.name() + ".pdf"), pdfBytes);
                }
                try (var pdf = Loader.loadPDF(pdfBytes)) {
                    var text = new PDFTextStripper().getText(pdf)
                            .toUpperCase(java.util.Locale.ROOT);
                    assertThat(text).as(filename + " " + profile)
                            .contains("FACTURA", "TOTAL", "FV-2026-1");
                    if (profile == InvoiceFiscalProfile.IGIC_MINORISTA) {
                        assertThat(text).contains("COMERCIANTE MINORISTA")
                                .containsAnyOf("12,10", "12.10")
                                .doesNotContain("DESGLOSE DE IMPUESTOS", "BASE IMPONIBLE",
                                        "CUOTA", "IMPUESTOS", "IMP. ", "IGIC INCLUIDO",
                                        "TIPO %", "IMP. %", "PRECIO NETO", "IMPORTE BRUTO",
                                        "DESCUENTOS", "11,308", "11.308", "11,31", "11.31");
                    } else {
                        assertThat(text).contains("DESGLOSE DE IMPUESTOS",
                                        "BASE IMPONIBLE", "CUOTA", "IMPUESTOS",
                                        "IMPORTE BRUTO", "DESCUENTOS",
                                        profile == InvoiceFiscalProfile.IVA ? "IVA" : "IGIC")
                                .doesNotContain("COMERCIANTE MINORISTA");
                        if (format == DocumentTemplateFormat.A4) {
                            assertThat(text).contains("PRECIO NETO");
                        }
                    }
                }
            }
        }
    }

    private static byte[] renderLegacyInvoiceTemplate(
            byte[] compiled, com.fasterxml.jackson.databind.node.ObjectNode data)
            throws Exception {
        var context = SafeJrxmlCompiler.secureContext();
        var parameters = new java.util.HashMap<String, Object>();
        parameters.put(JsonQueryExecuterFactory.JSON_INPUT_STREAM,
                new ByteArrayInputStream(new ObjectMapper().writeValueAsBytes(data)));
        parameters.put(InvoiceJasperRenderer.ISSUER_LOGO_STREAM_PARAMETER, null);
        parameters.put(InvoiceJasperRenderer.ISSUER_LOGO_PRESENT_PARAMETER, false);
        var print = JasperFillManager.getInstance(context).fill(
                new ByteArrayInputStream(compiled), parameters);
        return JasperExportManager.getInstance(context).exportToPdf(print);
    }

    private static CommercialDocument igicInvoice(Store store) {
        var document = new CommercialDocument(
                store.getId(), UUID.randomUUID(), CommercialDocumentType.FACTURA_VENTA,
                LocalDate.of(2026, 8, 10), UUID.randomUUID(), BigDecimal.ZERO);
        document.addLine(new DocumentLine(
                document, UUID.randomUUID(), 1, BigDecimal.ONE,
                "P-IGIC", "8430000000010", "Articulo", "VENTA",
                new BigDecimal("12.10"), BigDecimal.ZERO, true,
                "IGIC", new BigDecimal("7.00")));
        document.confirm("FV-2026-1", UUID.randomUUID(),
                Instant.parse("2026-08-10T09:00:00Z"), false);
        return document;
    }

    @Test
    void saleInvoiceUnitPriceContractPreservesNetAndDiscountedTotals() {
        var fixture = fixture();
        var document = new CommercialDocument(
                fixture.store().getId(), UUID.randomUUID(),
                CommercialDocumentType.FACTURA_VENTA, LocalDate.of(2026, 8, 10),
                UUID.randomUUID(), BigDecimal.ZERO);
        document.addLine(new DocumentLine(
                document, UUID.randomUUID(), 1, BigDecimal.ONE,
                "P-INCLUIDO", "Precio incluido", "VENTA",
                new BigDecimal("12.10"), BigDecimal.ZERO, true,
                "IGIC", new BigDecimal("7.00")));
        document.addLine(new DocumentLine(
                document, UUID.randomUUID(), 2, BigDecimal.ONE,
                "P-EXCLUIDO", "Precio excluido", "VENTA",
                new BigDecimal("10.00"), new BigDecimal("10.00"), false,
                "IGIC", new BigDecimal("7.00")));
        document.confirm("FV-2026-PRECIO", UUID.randomUUID(),
                Instant.parse("2026-08-10T09:00:00Z"), false);
        var compiler = new SafeJrxmlCompiler();
        var renderer = new InvoiceJasperRenderer(
                mock(DocumentTemplateRepository.class),
                new DocumentTemplateArtifactStorage(temporaryDirectory),
                compiler, new ObjectMapper(), new BuiltInDocumentJrxmlCatalog(compiler));

        var json = renderer.data(
                document, fixture.store(), fixture.company(), fixture.customer(),
                new InvoicePresentationSnapshot(
                        4, InvoiceFiscalProfile.IGIC_MINORISTA, null, List.of()),
                null);

        assertThat(json.at("/lines/0/unitPriceSale").decimalValue())
                .isEqualByComparingTo("12.10");
        assertThat(json.at("/lines/0/unitPriceNet").decimalValue())
                .isEqualByComparingTo("11.308411");
        assertThat(json.at("/lines/0/lineTotal").decimalValue())
                .isEqualByComparingTo("12.10");
        assertThat(json.at("/lines/1/unitPriceSale").decimalValue())
                .isEqualByComparingTo("10.700000");
        assertThat(json.at("/lines/1/unitPriceNet").decimalValue())
                .isEqualByComparingTo("10.00");
        assertThat(json.at("/lines/1/discountPercent").decimalValue())
                .isEqualByComparingTo("10.00");
        assertThat(json.at("/lines/1/lineTotal").decimalValue())
                .isEqualByComparingTo("9.63");
        assertThat(json.at("/totals/grandTotal").decimalValue())
                .isEqualByComparingTo("21.73");
    }

    @Test
    void legacySnapshotWithoutTemplateMigratesToTheIntegratedJrxmlAtPrintTime() {
        var fixture = fixture();
        var compiler = new SafeJrxmlCompiler();
        var renderer = new InvoiceJasperRenderer(
                mock(DocumentTemplateRepository.class),
                new DocumentTemplateArtifactStorage(temporaryDirectory),
                compiler,
                new ObjectMapper(),
                new BuiltInDocumentJrxmlCatalog(compiler));
        var legacySnapshot = new InvoicePresentationSnapshot(
                1, InvoiceFiscalProfile.IVA, null, List.of());

        assertThat(renderer.render(
                fixture.document(), fixture.store(), fixture.company(), fixture.customer(),
                legacySnapshot, null).orElseThrow())
                .startsWith(0x25, 0x50, 0x44, 0x46);
    }

    @Test
    void jsonContractUsesHistoricalBarcodeAndCollapsesUnitText() {
        var fixture = fixture();
        var renderer = new InvoiceJasperRenderer(
                mock(DocumentTemplateRepository.class),
                new DocumentTemplateArtifactStorage(temporaryDirectory),
                new SafeJrxmlCompiler(),
                new ObjectMapper(),
                new BuiltInDocumentJrxmlCatalog(new SafeJrxmlCompiler()));

        var json = renderer.data(
                fixture.document(), fixture.store(), fixture.company(), fixture.customer(),
                new InvoicePresentationSnapshot(
                        2, InvoiceFiscalProfile.IVA, "Gracias", List.of()),
                "https://www2.agenciatributaria.gob.es/qr",
                "data:image/png;base64,AA==");

        assertThat(json.at("/lines/0/barcode").asText()).isEqualTo("8430000000010");
        assertThat(json.at("/lines/0/unit").isNull()).isTrue();
        assertThat(json.at("/fiscal/qrRequired").asBoolean()).isTrue();
        assertThat(json.at("/observations").asText()).isEqualTo("Gracias");
        assertThat(json.at("/issuer/logoDataUri").asText())
                .isEqualTo("data:image/png;base64,AA==");
        assertThat(json.at("/showStoreName").asBoolean()).isTrue();
        assertThat(json.at("/issuer/headerPrimaryName").asText()).isEqualTo("Tienda Centro");
        assertThat(json.at("/issuer/headerSecondaryName").asText()).isEqualTo("TPV ERP SL");
        assertThat(json.at("/issuer/details").asText()).isEqualTo("""
                NIF: B12345678
                Calle Emisor 1
                35001 Las Palmas
                País: ES""");
        assertThat(json.at("/customer/details").asText()).isEqualTo("""
                Cód. cliente: C-001
                NIF: B87654321
                Calle Cliente 2
                35002 Las Palmas
                País: ES
                Tel.: 928000000""");
        assertThat(json.at("/totals/grossAmount").decimalValue())
                .isEqualByComparingTo("10.00");
        assertThat(json.at("/totals/discountTotal").decimalValue()).isZero();

        var companyOnlyJson = renderer.data(
                fixture.document(), fixture.store(), fixture.company(), fixture.customer(),
                new InvoicePresentationSnapshot(
                        5, InvoiceFiscalProfile.IVA, "Gracias", List.of(),
                        null, null, null, false),
                null, null);
        assertThat(companyOnlyJson.at("/showStoreName").asBoolean()).isFalse();
        assertThat(companyOnlyJson.at("/issuer/headerPrimaryName").asText())
                .isEqualTo("TPV ERP SL");
        assertThat(companyOnlyJson.at("/issuer/headerSecondaryName").isNull()).isTrue();
    }

    @Test
    void a4AndTicketInvoiceDataUseThePersistedF11TaxBreakdown() {
        var fixture = fixture();
        var document = new CommercialDocument(
                fixture.store().getId(), UUID.randomUUID(),
                CommercialDocumentType.FACTURA_VENTA,
                LocalDate.of(2026, 8, 17), UUID.randomUUID(), BigDecimal.ZERO);
        document.addLine(new DocumentLine(
                document, UUID.randomUUID(), 1, BigDecimal.ONE,
                "P-F11", "Producto F11", "VENTA", new BigDecimal("40.00"),
                BigDecimal.ZERO, true, "IVA", new BigDecimal("21.00")));
        document.addLine(new DocumentLineCommand(
                null, BigDecimal.ONE, "DESCUENTO", "Descuento", null,
                new BigDecimal("-5.00"), BigDecimal.ZERO, true,
                "IVA", new BigDecimal("21.00"),
                DocumentLineType.MANUAL_DISCOUNT, null, null, null)
                .toEntity(document, 2));
        document.confirm("FV-2026-F11", UUID.randomUUID(),
                Instant.parse("2026-08-17T09:00:00Z"), false);
        var renderer = new InvoiceJasperRenderer(
                mock(DocumentTemplateRepository.class),
                new DocumentTemplateArtifactStorage(temporaryDirectory),
                new SafeJrxmlCompiler(),
                new ObjectMapper(),
                new BuiltInDocumentJrxmlCatalog(new SafeJrxmlCompiler()));

        var json = renderer.data(
                document, fixture.store(), fixture.company(), fixture.customer(),
                new InvoicePresentationSnapshot(
                        2, InvoiceFiscalProfile.IVA, null, List.of()),
                null, null);

        assertThat(json.at("/totals/grandTotal").decimalValue())
                .isEqualByComparingTo("35.00");
        assertThat(json.at("/totals/taxableBase").decimalValue())
                .isEqualByComparingTo("28.93");
        assertThat(json.at("/totals/taxTotal").decimalValue())
                .isEqualByComparingTo("6.07");
        assertThat(json.at("/taxBreakdown/0/taxableBase").decimalValue())
                .isEqualByComparingTo("28.93");
        assertThat(json.at("/taxBreakdown/0/taxAmount").decimalValue())
                .isEqualByComparingTo("6.07");
    }

    @Test
    void separatesMemberBalanceFromInvoiceArticlesAndDiscounts() {
        var fixture = fixture();
        var document = new CommercialDocument(
                fixture.store().getId(), UUID.randomUUID(),
                CommercialDocumentType.FACTURA_VENTA,
                LocalDate.of(2026, 8, 19), UUID.randomUUID(), BigDecimal.ZERO);
        document.addLine(new DocumentLine(
                document, UUID.randomUUID(), 1, BigDecimal.ONE,
                "P-MIEMBRO", "Producto miembro", "VENTA", new BigDecimal("20.00"),
                BigDecimal.ZERO, true, "IVA", new BigDecimal("21.00")));
        document.addLine(new DocumentLineCommand(
                null, BigDecimal.ONE, "SALDO DE MIEMBRO", "Saldo de miembro", null,
                new BigDecimal("-6.00"), BigDecimal.ZERO, true,
                "IVA", new BigDecimal("21.00"),
                DocumentLineType.MEMBER_BALANCE, null, null, null)
                .toEntity(document, 2));
        document.confirm("FV-2026-MIEMBRO", UUID.randomUUID(),
                Instant.parse("2026-08-19T09:00:00Z"), false);
        var renderer = new InvoiceJasperRenderer(
                mock(DocumentTemplateRepository.class),
                new DocumentTemplateArtifactStorage(temporaryDirectory),
                new SafeJrxmlCompiler(),
                new ObjectMapper(),
                new BuiltInDocumentJrxmlCatalog(new SafeJrxmlCompiler()));

        var json = renderer.data(
                document, fixture.store(), fixture.company(), fixture.customer(),
                new InvoicePresentationSnapshot(
                        2, InvoiceFiscalProfile.IVA, null, List.of()),
                null, null);

        assertThat(json.at("/lines").size()).isEqualTo(1);
        assertThat(json.at("/lines/0/articleName").asText()).isEqualTo("Producto miembro");
        assertThat(json.at("/totals/memberBalanceTotal").decimalValue())
                .isEqualByComparingTo("6.00");
        assertThat(json.at("/totals/discountTotal").decimalValue()).isZero();
        assertThat(json.at("/totals/grandTotal").decimalValue())
                .isEqualByComparingTo("14.00");
        assertThat(json.at("/totals/taxableBase").decimalValue())
                .isEqualByComparingTo("11.57");
        assertThat(json.at("/totals/taxTotal").decimalValue())
                .isEqualByComparingTo("2.43");
    }

    @Test
    void fiscalJsonUsesFrozenMetadataWithoutInferringAnythingFromTheUrl() {
        var fixture = fixture();
        var compiler = new SafeJrxmlCompiler();
        var renderer = new InvoiceJasperRenderer(
                mock(DocumentTemplateRepository.class),
                new DocumentTemplateArtifactStorage(temporaryDirectory),
                compiler,
                new ObjectMapper(),
                new BuiltInDocumentJrxmlCatalog(compiler));
        var presentation = new InvoicePresentationSnapshot(
                4, InvoiceFiscalProfile.IVA, null, List.of());
        var contradictoryUrl = "https://prewww2.aeat.es/wlpl/TIKE-CONT/ValidarQR"
                + "?nif=B12345678&numserie=FV-1&fecha=10-08-2026&importe=10.00";
        var frozen = new FiscalPrintView(
                "FORMATO-CONGELADO",
                "GENERADOR-CONGELADO",
                FiscalMode.NO_VERIFACTU,
                FiscalEndpointEnvironment.PRODUCTION,
                contradictoryUrl,
                "B".repeat(64),
                "PREFIJO CONGELADO:",
                null,
                null,
                "Obligado congelado SL",
                "B12345678",
                Map.of(
                        "linea1", "Calle congelada 7",
                        "codigoPostal", "35007",
                        "ciudad", "Telde",
                        "provincia", "Las Palmas",
                        "pais", "ES"));

        var json = renderer.dataWithFiscalSnapshot(
                fixture.document(), fixture.store(), fixture.company(), fixture.customer(),
                presentation, frozen, null);

        assertThat(json.at("/fiscal/mode").asText()).isEqualTo("NO_VERIFACTU");
        assertThat(json.at("/fiscal/environment").asText()).isEqualTo("PRODUCTION");
        assertThat(json.at("/fiscal/testData").asBoolean()).isFalse();
        assertThat(json.at("/fiscal/qrPrefix").asText()).isEqualTo("PREFIJO CONGELADO:");
        assertThat(json.at("/fiscal/legend").isNull()).isTrue();
        assertThat(json.at("/fiscal/testNotice").isNull()).isTrue();
        assertThat(json.at("/fiscal/formatVersion").asText()).isEqualTo("FORMATO-CONGELADO");
        assertThat(json.at("/fiscal/generatorVersion").asText())
                .isEqualTo("GENERADOR-CONGELADO");
        assertThat(json.at("/issuer/legalName").asText())
                .isEqualTo("Obligado congelado SL");
        assertThat(json.at("/issuer/taxId").asText()).isEqualTo("B12345678");
        assertThat(json.at("/issuer/address/line1").asText())
                .isEqualTo("Calle congelada 7");
        assertThat(json.at("/fiscal/verificationUrl").asText()).isEqualTo(contradictoryUrl);
    }

    private static String decodeQr(BufferedImage image) throws Exception {
        return new MultiFormatReader().decode(
                new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image))),
                Map.of(DecodeHintType.TRY_HARDER, Boolean.TRUE)).getText();
    }

    @Test
    void rendersValuedDeliveryNoteWithoutCustomerAndNeverExposesFiscalQrOrPayments()
            throws Exception {
        var fixture = fixture();
        var document = new CommercialDocument(
                fixture.store().getId(), UUID.randomUUID(),
                CommercialDocumentType.ALBARAN_VENTA,
                LocalDate.of(2026, 8, 10), UUID.randomUUID(), BigDecimal.ZERO);
        document.addLine(new DocumentLine(
                document, UUID.randomUUID(), 1, BigDecimal.ONE,
                "P-001", "8430000000010", "Articulo", "VENTA",
                new BigDecimal("12.10"), BigDecimal.ZERO, true,
                "IGIC", new BigDecimal("7.00")));
        document.confirm("AV-2026-1", UUID.randomUUID(),
                Instant.parse("2026-08-10T09:00:00Z"), false);
        var template = DocumentTemplate.storeDraft(
                fixture.store(), DocumentTemplateType.ALBARAN_VENTA,
                "ALBARAN_TIENDA", 1, "Albaran tienda", UUID.randomUUID(),
                Instant.parse("2026-08-10T08:00:00Z"));
        var compiler = new SafeJrxmlCompiler();
        var compiled = compiler.compile(jrxml().getBytes(StandardCharsets.UTF_8));
        var storage = new DocumentTemplateArtifactStorage(temporaryDirectory);
        storage.write(template.getId(), compiled.source(), compiled.compiled());
        template.validateArtifact(
                SafeJrxmlCompiler.DATA_SCHEMA_VERSION,
                template.getId().toString(), compiled.sha256(),
                Instant.parse("2026-08-10T10:00:00Z"));
        template.activate(Instant.parse("2026-08-10T10:01:00Z"));
        var templates = mock(DocumentTemplateRepository.class);
        when(templates.findPrintableTemplate(
                template.getId(), fixture.company().getId(), fixture.store().getId()))
                .thenReturn(Optional.of(template));
        var renderer = new InvoiceJasperRenderer(
                templates, storage, compiler, new ObjectMapper(),
                new BuiltInDocumentJrxmlCatalog(compiler));
        var presentation = snapshot(template, compiled.sha256());

        var json = renderer.data(
                document, fixture.store(), fixture.company(), null, presentation,
                "https://www2.agenciatributaria.gob.es/qr-no-debe-usarse");
        var pdf = renderer.render(
                document, fixture.store(), fixture.company(), null, presentation,
                "https://www2.agenciatributaria.gob.es/qr-no-debe-usarse")
                .orElseThrow();

        assertThat(pdf).startsWith(0x25, 0x50, 0x44, 0x46);
        assertThat(json.at("/document/type").asText()).isEqualTo("ALBARAN_VENTA");
        assertThat(json.at("/document/title").asText()).isEqualTo("ALBARAN");
        assertThat(json.at("/customer/legalName").isMissingNode()).isTrue();
        assertThat(json.at("/fiscal/qrRequired").asBoolean()).isFalse();
        assertThat(json.at("/fiscal/mode").asText()).isEqualTo("NOT_APPLICABLE");
        assertThat(json.at("/fiscal/verificationUrl").isNull()).isTrue();
        assertThat(json.at("/payment/bankAccounts")).isEmpty();
        assertThat(json.at("/payment/method").isMissingNode()).isTrue();
    }

    private static Fixture fixture() {
        var address = Map.of(
                "linea1", "Calle Emisor 1",
                "codigoPostal", "35001",
                "ciudad", "Las Palmas",
                "provincia", "Las Palmas",
                "pais", "ES");
        var company = new Company("B12345678", "TPV ERP SL", address);
        var store = new Store(company, "001", "Tienda Centro", address,
                UUID.randomUUID().toString(), "Atlantic/Canary", "EUR", "es-ES");
        var customer = new Customer(company, "Cliente SL", DocumentType.CIF,
                "B87654321", new FiscalAddress(
                        "Calle Cliente 2", "35002", "Las Palmas",
                        "Las Palmas", "ES"), "928000000", null, null,
                CustomerRate.VENTA, BigDecimal.ZERO);
        customer.assignClientCode(store.getId(), "C-001");
        var document = new CommercialDocument(
                store.getId(), UUID.randomUUID(), CommercialDocumentType.FACTURA_VENTA,
                LocalDate.of(2026, 8, 10), UUID.randomUUID(), BigDecimal.ZERO);
        document.addLine(new DocumentLine(
                document, UUID.randomUUID(), 1, BigDecimal.ONE,
                "P-001", "8430000000010", "Articulo", "VENTA",
                new BigDecimal("12.10"), BigDecimal.ZERO, true,
                "IVA", new BigDecimal("21.00")));
        document.confirm("FV-2026-1", UUID.randomUUID(),
                Instant.parse("2026-08-10T09:00:00Z"), false);
        var template = DocumentTemplate.storeDraft(
                store, DocumentTemplateType.FACTURA_VENTA, "FACTURA_TIENDA",
                1, "Factura tienda", UUID.randomUUID(),
                Instant.parse("2026-08-10T08:00:00Z"));
        return new Fixture(company, store, customer, document, template);
    }

    private static InvoicePresentationSnapshot snapshot(
            DocumentTemplate template, String sha256) {
        return new InvoicePresentationSnapshot(
                2, InvoiceFiscalProfile.IVA, null, List.of(),
                new InvoicePresentationSnapshot.TemplateReference(
                        template.getId(), template.getCode(),
                        template.getTemplateVersion(), SafeJrxmlCompiler.DATA_SCHEMA_VERSION,
                        sha256, false));
    }

    private static String jrxml() {
        return """
                <jasperReport name="barcode_invoice" language="java"
                  pageWidth="300" pageHeight="200" columnWidth="260"
                  leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20"
                  uuid="2d8d17da-050c-4bff-8c8b-78156b8660b3">
                  <query language="jsonql"><![CDATA[lines]]></query>
                  <field name="barcode" class="java.lang.String">
                    <property name="net.sf.jasperreports.jsonql.field.expression" value="barcode"/>
                  </field>
                  <detail>
                    <band height="20">
                      <element kind="textField" x="0" y="0" width="250" height="20">
                        <expression><![CDATA[$F{barcode}]]></expression>
                      </element>
                    </band>
                  </detail>
                </jasperReport>
                """;
    }

    private static String logoJrxml() {
        return """
                <jasperReport name="logo_invoice" language="java"
                  pageWidth="300" pageHeight="200" columnWidth="260"
                  leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
                  <parameter name="TPV_ISSUER_LOGO_STREAM" class="java.io.InputStream"/>
                  <parameter name="TPV_ISSUER_LOGO_PRESENT" class="java.lang.Boolean"/>
                  <query language="jsonql"><![CDATA[lines]]></query>
                  <field name="issuerLegalName" class="java.lang.String">
                    <property name="net.sf.jasperreports.jsonql.field.expression"
                      value="$.issuer.legalName"/>
                  </field>
                  <title height="40">
                    <element kind="image" x="0" y="0" width="30" height="30"
                      scaleImage="RetainShape">
                      <printWhenExpression><![CDATA[java.lang.Boolean.TRUE.equals($P{TPV_ISSUER_LOGO_PRESENT})]]></printWhenExpression>
                      <expression><![CDATA[$P{TPV_ISSUER_LOGO_STREAM}]]></expression>
                    </element>
                    <element kind="textField" x="70" y="0" width="150" height="20">
                      <printWhenExpression><![CDATA[java.lang.Boolean.TRUE.equals($P{TPV_ISSUER_LOGO_PRESENT})]]></printWhenExpression>
                      <expression><![CDATA[$F{issuerLegalName}]]></expression>
                    </element>
                    <element kind="textField" x="0" y="0" width="220" height="20">
                      <printWhenExpression><![CDATA[!java.lang.Boolean.TRUE.equals($P{TPV_ISSUER_LOGO_PRESENT})]]></printWhenExpression>
                      <expression><![CDATA[$F{issuerLegalName}]]></expression>
                    </element>
                  </title>
                </jasperReport>
                """;
    }

    private static String logoDataUri() throws Exception {
        var image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, 0x00112233);
            }
        }
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return "data:image/png;base64,"
                + java.util.Base64.getEncoder().encodeToString(output.toByteArray());
    }

    private static int nonWhitePixelsInLogoArea(
            org.apache.pdfbox.pdmodel.PDDocument document) throws Exception {
        BufferedImage page = new PDFRenderer(document).renderImageWithDPI(0, 72);
        int count = 0;
        for (int y = 20; y < 50; y++) {
            for (int x = 20; x < 50; x++) {
                if ((page.getRGB(x, y) & 0x00ffffff) != 0x00ffffff) {
                    count++;
                }
            }
        }
        return count;
    }

    private static float textX(
            org.apache.pdfbox.pdmodel.PDDocument document, String expected) throws Exception {
        var positions = new java.util.ArrayList<Float>();
        var stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> textPositions) {
                if (text.contains(expected) && !textPositions.isEmpty()) {
                    positions.add(textPositions.get(0).getXDirAdj());
                }
            }
        };
        stripper.getText(document);
        assertThat(positions).isNotEmpty();
        return positions.get(0);
    }

    private static TextBounds textBounds(
            org.apache.pdfbox.pdmodel.PDDocument document, String expected) throws Exception {
        var matches = new java.util.ArrayList<TextBounds>();
        var stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> textPositions) {
                if (text.contains(expected) && !textPositions.isEmpty()) {
                    float top = textPositions.stream()
                            .map(TextPosition::getYDirAdj)
                            .min(Float::compareTo)
                            .orElseThrow();
                    float bottom = textPositions.stream()
                            .map(position -> position.getYDirAdj() + position.getHeightDir())
                            .max(Float::compareTo)
                            .orElseThrow();
                    matches.add(new TextBounds(top, bottom));
                }
            }
        };
        stripper.getText(document);
        assertThat(matches).as(expected).singleElement();
        return matches.get(0);
    }

    private record TextBounds(float top, float bottom) {}

    private record Fixture(
            Company company,
            Store store,
            Customer customer,
            CommercialDocument document,
            DocumentTemplate template) {}
}
