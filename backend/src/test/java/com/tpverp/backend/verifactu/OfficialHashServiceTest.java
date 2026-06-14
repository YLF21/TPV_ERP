package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;

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
}
