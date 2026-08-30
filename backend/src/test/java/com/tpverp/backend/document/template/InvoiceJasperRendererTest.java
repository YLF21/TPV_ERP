package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.DocumentLine;
import com.tpverp.backend.document.DocumentLineCommand;
import com.tpverp.backend.document.DocumentLineType;
import com.tpverp.backend.document.DocumentPayment;
import com.tpverp.backend.document.FiscalPrintView;
import com.tpverp.backend.document.InvoiceFiscalProfile;
import com.tpverp.backend.document.InvoicePresentationSnapshot;
import com.tpverp.backend.document.PaymentMethod;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.party.Customer;
import com.tpverp.backend.party.CustomerRate;
import com.tpverp.backend.party.DocumentType;
import com.tpverp.backend.party.FiscalAddress;
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
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InvoiceJasperRendererTest {

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
                4, InvoiceFiscalProfile.IVA, "Gracias por su confianza", List.of(),
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
                            "TOTAL FACTURA", "Gracias por su confianza");
        }
        var raster = ImageIO.read(new ByteArrayInputStream(rendered.ticketRasterPng()));
        assertThat(raster.getWidth()).isEqualTo(576);
        assertThat(raster.getHeight()).isBetween(100, 5_073);
        assertThat(decodeQr(raster)).isEqualTo("https://www2.agenciatributaria.gob.es/qr");
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
                "P-SOCIO", "Producto socio", "VENTA", new BigDecimal("20.00"),
                BigDecimal.ZERO, true, "IVA", new BigDecimal("21.00")));
        document.addLine(new DocumentLineCommand(
                null, BigDecimal.ONE, "SALDO SOCIO", "Saldo socio", null,
                new BigDecimal("-6.00"), BigDecimal.ZERO, true,
                "IVA", new BigDecimal("21.00"),
                DocumentLineType.MEMBER_BALANCE, null, null, null)
                .toEntity(document, 2));
        document.confirm("FV-2026-SOCIO", UUID.randomUUID(),
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
        assertThat(json.at("/lines/0/articleName").asText()).isEqualTo("Producto socio");
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

    private record Fixture(
            Company company,
            Store store,
            Customer customer,
            CommercialDocument document,
            DocumentTemplate template) {}
}
