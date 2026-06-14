package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class OfficialHashServiceTest {

    private final OfficialHashService service = new OfficialHashService();

    @Test
    void calculaPrimerRegistroDeAltaDelEjemploAeat() {
        var input = new AltaHashInput(
                "89890001K",
                "12345678/G33",
                "01-01-2024",
                "F1",
                new BigDecimal("12.35"),
                new BigDecimal("123.45"),
                null,
                OffsetDateTime.parse("2024-01-01T19:20:30+01:00"));

        assertThat(service.hash(input))
                .isEqualTo("3C464DAF61ACB827C65FDA19F352A4E3BDC2C640E9E9FC4CC058073F38F12F60");
    }

    @Test
    void calculaAltaEncadenadaDelEjemploAeat() {
        var input = new AltaHashInput(
                "89890001K",
                "12345679/G34",
                "01-01-2024",
                "F1",
                new BigDecimal("12.35"),
                new BigDecimal("123.45"),
                "3C464DAF61ACB827C65FDA19F352A4E3BDC2C640E9E9FC4CC058073F38F12F60",
                OffsetDateTime.parse("2024-01-01T19:20:35+01:00"));

        assertThat(service.hash(input))
                .isEqualTo("F7B94CFD8924EDFF273501B01EE5153E4CE8F259766F88CF6ACB8935802A2B97");
    }

    @Test
    void calculaAnulacionDelEjemploAeat() {
        var input = new CancellationHashInput(
                "89890001K",
                "12345679/G34",
                "01-01-2024",
                "F7B94CFD8924EDFF273501B01EE5153E4CE8F259766F88CF6ACB8935802A2B97",
                OffsetDateTime.parse("2024-01-01T19:20:40+01:00"));

        assertThat(service.hash(input))
                .isEqualTo("177547C0D57AC74748561D054A9CEC14B4C4EA23D1BEFD6F2E69E3A388F90C68");
    }

    @Test
    void conservaLosSegundosCeroEnLaFechaHora() {
        var input = new AltaHashInput(
                "89890001K",
                "12345678/G33",
                "01-01-2024",
                "F1",
                new BigDecimal("12.35"),
                new BigDecimal("123.45"),
                null,
                OffsetDateTime.parse("2024-01-01T19:20:00+01:00"));

        assertThat(service.hash(input))
                .isEqualTo("6EE4110A77C636565E0E095B309E8AA4A18CFB5B3A880A0859F5F14E4E2B01A9");
    }

    @Test
    void normalizaLosTextosObligatorios() {
        var generatedAt = OffsetDateTime.parse("2024-01-01T19:20:30+01:00");
        var alta = new AltaHashInput(
                " 89890001K ", " 001 ", " 01-01-2024 ", " F1 ",
                BigDecimal.ONE, BigDecimal.TEN, null, generatedAt);
        var cancellation = new CancellationHashInput(
                " 89890001K ", " 001 ", " 01-01-2024 ", null, generatedAt);

        assertThat(alta)
                .extracting(
                        AltaHashInput::issuerTaxId,
                        AltaHashInput::invoiceNumber,
                        AltaHashInput::issueDate,
                        AltaHashInput::invoiceType)
                .containsExactly("89890001K", "001", "01-01-2024", "F1");
        assertThat(cancellation)
                .extracting(
                        CancellationHashInput::issuerTaxId,
                        CancellationHashInput::cancelledInvoiceNumber,
                        CancellationHashInput::cancelledIssueDate)
                .containsExactly("89890001K", "001", "01-01-2024");
        assertThat(new AltaHashInput(
                "89890001K", "001", "01-01-2024", "F1",
                BigDecimal.ONE, BigDecimal.TEN, " HASH ", generatedAt).previousHash())
                .isEqualTo("HASH");
    }

    @Test
    void rechazaCamposObligatoriosInvalidosEnAlta() {
        var generatedAt = OffsetDateTime.parse("2024-01-01T19:20:30+01:00");

        assertInvalidAlta(null, "001", "01-01-2024", "F1", BigDecimal.ONE, BigDecimal.TEN, generatedAt);
        assertInvalidAlta(" ", "001", "01-01-2024", "F1", BigDecimal.ONE, BigDecimal.TEN, generatedAt);
        assertInvalidAlta("89890001K", null, "01-01-2024", "F1", BigDecimal.ONE, BigDecimal.TEN, generatedAt);
        assertInvalidAlta("89890001K", " ", "01-01-2024", "F1", BigDecimal.ONE, BigDecimal.TEN, generatedAt);
        assertInvalidAlta("89890001K", "001", null, "F1", BigDecimal.ONE, BigDecimal.TEN, generatedAt);
        assertInvalidAlta("89890001K", "001", " ", "F1", BigDecimal.ONE, BigDecimal.TEN, generatedAt);
        assertInvalidAlta("89890001K", "001", "01-01-2024", null, BigDecimal.ONE, BigDecimal.TEN, generatedAt);
        assertInvalidAlta("89890001K", "001", "01-01-2024", " ", BigDecimal.ONE, BigDecimal.TEN, generatedAt);
        assertInvalidAlta("89890001K", "001", "01-01-2024", "F1", null, BigDecimal.TEN, generatedAt);
        assertInvalidAlta("89890001K", "001", "01-01-2024", "F1", BigDecimal.ONE, null, generatedAt);
        assertInvalidAlta("89890001K", "001", "01-01-2024", "F1", BigDecimal.ONE, BigDecimal.TEN, null);
        assertThatThrownBy(() -> new AltaHashInput(
                "89890001K", "001", "01-01-2024", "F1",
                BigDecimal.ONE, BigDecimal.TEN, " ", generatedAt))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rechazaCamposObligatoriosInvalidosEnAnulacion() {
        var generatedAt = OffsetDateTime.parse("2024-01-01T19:20:30+01:00");

        assertInvalidCancellation(null, "001", "01-01-2024", generatedAt);
        assertInvalidCancellation(" ", "001", "01-01-2024", generatedAt);
        assertInvalidCancellation("89890001K", null, "01-01-2024", generatedAt);
        assertInvalidCancellation("89890001K", " ", "01-01-2024", generatedAt);
        assertInvalidCancellation("89890001K", "001", null, generatedAt);
        assertInvalidCancellation("89890001K", "001", " ", generatedAt);
        assertInvalidCancellation("89890001K", "001", "01-01-2024", null);
        assertThatThrownBy(() -> new CancellationHashInput(
                "89890001K", "001", "01-01-2024", " ", generatedAt))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertInvalidAlta(
            String issuerTaxId,
            String invoiceNumber,
            String issueDate,
            String invoiceType,
            BigDecimal totalTax,
            BigDecimal totalAmount,
            OffsetDateTime generatedAt) {
        assertThatThrownBy(() -> new AltaHashInput(
                issuerTaxId, invoiceNumber, issueDate, invoiceType,
                totalTax, totalAmount, null, generatedAt))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertInvalidCancellation(
            String issuerTaxId,
            String invoiceNumber,
            String issueDate,
            OffsetDateTime generatedAt) {
        assertThatThrownBy(() -> new CancellationHashInput(
                issuerTaxId, invoiceNumber, issueDate, null, generatedAt))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
