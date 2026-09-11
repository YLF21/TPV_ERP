package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.tpverp.backend.document.CustomerModel347Report;
import com.tpverp.backend.document.CustomerModel347Report.Party;
import com.tpverp.backend.document.CustomerModel347Report.Quarter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class CustomerModel347JasperRendererTest {

    private static final CustomerModel347JasperRenderer RENDERER =
            new CustomerModel347JasperRenderer(new SafeJrxmlCompiler());

    @Test
    void rendersA4WithFourQuarterPeriodsSignedAmountsAndAnnualTotal() throws Exception {
        var bytes = RENDERER.render(sample(), "es");
        try (var pdf = Loader.loadPDF(bytes)) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(1);
            assertThat(pdf.getPage(0).getMediaBox().getWidth()).isCloseTo(595f, within(1f));
            assertThat(pdf.getPage(0).getMediaBox().getHeight()).isCloseTo(842f, within(1f));
            var text = text(pdf);
            assertThat(text).contains(
                    "MODELO 347", "Ejercicio: 2024", "EMISOR", "CLIENTE", "Código: C-0042",
                    "Comercial Álvarez, S.L.", "Óptica Peña", "B00000000", "B11111111",
                    "Avenida de la Constitución", "Calle del Océano",
                    "T1", "01/01/2024 - 31/03/2024", "12.500,50 EUR",
                    "T2", "01/04/2024 - 30/06/2024", "-234,56 EUR",
                    "T3", "01/07/2024 - 30/09/2024", "0,00 EUR",
                    "T4", "01/10/2024 - 31/12/2024", "450,10 EUR",
                    "TOTAL ANUAL", "12.716,04 EUR", "Todas las tiendas de la empresa",
                    "pagadas o pendientes", "Sin importe mínimo",
                    "no constituye una declaración oficial");
            writePreview(bytes, pdf, "es");
        }
    }

    @Test
    void retainsZeroQuartersAndNegativeAnnualTotals() throws Exception {
        var base = sample();
        var report = new CustomerModel347Report(base.year(), base.issuer(), base.customer(),
                List.of(new Quarter(1, BigDecimal.ZERO, 0),
                        new Quarter(2, new BigDecimal("-10.25"), 1),
                        new Quarter(3, BigDecimal.ZERO, 0),
                        new Quarter(4, BigDecimal.ZERO, 0)), new BigDecimal("-10.25"));
        try (var pdf = Loader.loadPDF(RENDERER.render(report, null))) {
            assertThat(text(pdf)).contains("T1", "T2", "T3", "T4", "0,00 EUR", "-10,25 EUR");
            assertThat(text(pdf).split("-10,25 EUR", -1)).hasSize(3);
        }
    }

    @Test
    void translatesEnglishAndChineseAndRetainsChinesePartyNamesInSpanish() throws Exception {
        try (var pdf = Loader.loadPDF(RENDERER.render(sample(), "en-GB"))) {
            assertThat(text(pdf)).contains(
                    "FORM 347", "Year: 2024", "ISSUER", "CUSTOMER", "Q1", "Q4",
                    "12,716.04 EUR", "ANNUAL TOTAL", "not an official Form 347 filing");
        }
        var base = sample();
        var report = new CustomerModel347Report(base.year(), base.issuer(),
                new Party("C-0042", "B11111111", "华丰商贸有限公司",
                        "浙江省杭州市西湖区文三路一百号 商业大厦三层"),
                base.quarters(), base.annualTotal());
        var bytes = RENDERER.render(report, "zh-CN");
        try (var pdf = Loader.loadPDF(bytes)) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(1);
            assertThat(text(pdf)).contains("347 表", "年度", "客户", "年度合计", "华丰商贸有限公司",
                    "浙江省杭州市", "12,716.04 EUR", "并非正式", "Comercial Álvarez, S.L.", "España")
                    .doesNotContain("\ufffd");
            writePreview(bytes, pdf, "zh");
        }
        try (var pdf = Loader.loadPDF(RENDERER.render(report, "es"))) {
            assertThat(text(pdf)).contains("MODELO 347", "华丰商贸有限公司", "Comercial Álvarez, S.L.",
                    "España").doesNotContain("\ufffd");
        }
    }

    @Test
    void preservesMixedScriptNamesAndTreatsMarkupCharactersAsLiteralData() throws Exception {
        var base = sample();
        var report = new CustomerModel347Report(base.year(), base.issuer(),
                new Party("C-0042", "B11111111", "Óptica <b>Peña</b> & 华丰",
                        "浙江省杭州市 / Avenida de la Constitución, España"),
                base.quarters(), base.annualTotal());
        try (var pdf = Loader.loadPDF(RENDERER.render(report, "zh"))) {
            assertThat(text(pdf)).contains("Óptica <b>Peña</b> & 华丰", "浙江省杭州市",
                    "Avenida de la Constitución, España").doesNotContain("\ufffd");
        }
    }

    @Test
    void wrapsLongNamesAndAddressesWithoutLosingTheirEnds() throws Exception {
        var base = sample();
        var address = "Avenida de la Constitución y de los Derechos Humanos, número 128, "
                + "Edificio Comercial del Atlántico, portal tercero, entreplanta, oficina 42, "
                + "Polígono Industrial de la Ciudad, barrio de San Cristóbal, distrito del Puerto, "
                + "35001 Las Palmas de Gran Canaria, provincia de Las Palmas, España. ";
        var report = new CustomerModel347Report(base.year(),
                new Party("", "B00000000", "Comercial Álvarez y Asociados de Canarias, Sociedad Limitada",
                        address + "FINAL EMISOR"),
                new Party("C-0042", "B11111111", "Óptica Peña y Servicios Profesionales de Canarias, S.L.",
                        address + "FINAL CLIENTE"), base.quarters(), base.annualTotal());
        var bytes = RENDERER.render(report, "es");
        try (var pdf = Loader.loadPDF(bytes)) {
            assertThat(text(pdf)).contains(
                    "Comercial Álvarez y Asociados de Canarias, Sociedad Limitada",
                    "Óptica Peña y Servicios Profesionales de Canarias, S.L.",
                    "FINAL EMISOR", "FINAL CLIENTE", "31/12/2024", "12.716,04 EUR",
                    "no constituye una declaración oficial");
            writePreview(bytes, pdf, "long-addresses");
        }
    }

    private static CustomerModel347Report sample() {
        return new CustomerModel347Report(2024,
                new Party("", "B00000000", "Comercial Álvarez, S.L.",
                        "Avenida de la Constitución, 24\n35001 Las Palmas de Gran Canaria, España"),
                new Party("C-0042", "B11111111", "Óptica Peña",
                        "Calle del Océano, 18\n38001 Santa Cruz de Tenerife, España"),
                List.of(new Quarter(1, new BigDecimal("12500.50"), 14),
                        new Quarter(2, new BigDecimal("-234.56"), 1),
                        new Quarter(3, BigDecimal.ZERO, 0),
                        new Quarter(4, new BigDecimal("450.10"), 2)),
                new BigDecimal("12716.04"));
    }

    private static String text(PDDocument pdf) throws Exception {
        return new PDFTextStripper().getText(pdf).replaceAll("\\s+", " ");
    }

    private static void writePreview(byte[] bytes, PDDocument pdf, String locale) throws Exception {
        var directory = Path.of("target");
        Files.createDirectories(directory);
        Files.write(directory.resolve("model347-preview-" + locale + ".pdf"), bytes);
        var image = new PDFRenderer(pdf).renderImageWithDPI(0, 120, ImageType.RGB);
        ImageIO.write(image, "png", directory.resolve("model347-preview-" + locale + ".png").toFile());
    }
}
