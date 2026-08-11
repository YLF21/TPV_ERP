package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.DocumentLine;
import com.tpverp.backend.document.InvoiceFiscalProfile;
import com.tpverp.backend.document.InvoicePresentationSnapshot;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.party.Customer;
import com.tpverp.backend.party.CustomerRate;
import com.tpverp.backend.party.DocumentType;
import com.tpverp.backend.party.FiscalAddress;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
                templates, storage, compiler, new ObjectMapper());

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
                templates, storage, compiler, new ObjectMapper());
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
    void builtInTemplateKeepsExistingHtmlFallback() {
        var fixture = fixture();
        var renderer = new InvoiceJasperRenderer(
                mock(DocumentTemplateRepository.class),
                new DocumentTemplateArtifactStorage(temporaryDirectory),
                new SafeJrxmlCompiler(),
                new ObjectMapper());
        var snapshot = new InvoicePresentationSnapshot(
                2, InvoiceFiscalProfile.IVA, null, List.of(),
                new InvoicePresentationSnapshot.TemplateReference(
                        null, "FACTURA_A4", 1, 1, null, true));

        assertThat(renderer.render(
                fixture.document(), fixture.store(), fixture.company(), fixture.customer(),
                snapshot, null)).isEmpty();
    }

    @Test
    void jsonContractUsesHistoricalBarcodeAndCollapsesUnitText() {
        var fixture = fixture();
        var renderer = new InvoiceJasperRenderer(
                mock(DocumentTemplateRepository.class),
                new DocumentTemplateArtifactStorage(temporaryDirectory),
                new SafeJrxmlCompiler(),
                new ObjectMapper());

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
        assertThat(json.at("/totals/grossAmount").decimalValue())
                .isEqualByComparingTo("10.00");
        assertThat(json.at("/totals/discountTotal").decimalValue()).isZero();
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
                templates, storage, compiler, new ObjectMapper());
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
