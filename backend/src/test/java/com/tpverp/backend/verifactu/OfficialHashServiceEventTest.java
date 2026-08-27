package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class OfficialHashServiceEventTest {
    @Test
    void generaHuellaEventoEnMayusculasYConLosCamposAEAT() {
        var hash = new OfficialHashService().hash(new FiscalEventHashInput(
                "89890001K", "", "77", "1.0.03", "383", "11111111H", "01", "",
                OffsetDateTime.parse("2024-09-13T12:56:56+02:00")));
        assertThat(hash).hasSize(64).isUpperCase();
        assertThat(hash).isEqualTo("78765283F55C68FB9EC73DED9D254FBE5B36550C7930F6B3FE7032BA030A4498");
    }

    @Test
    void conservaLosSegundosEnLaRepresentacionCanonicaAunqueSeanCero() throws Exception {
        var timestamp = OffsetDateTime.parse("2026-01-15T13:00:00+01:00");
        var hash = new OfficialHashService().hash(new FiscalEventHashInput(
                "B00000000", "", "TPVERP", "4.1.0", "INST-DEV-1",
                "B12345678", "01", "", timestamp));
        var canonical = "NIF=B00000000&ID=&IdSistemaInformatico=TPVERP&Version=4.1.0"
                + "&NumeroInstalacion=INST-DEV-1&NIF=B12345678&TipoEvento=01"
                + "&HuellaEvento=&FechaHoraHusoGenEvento=2026-01-15T13:00:00+01:00";
        var expected = java.util.HexFormat.of().withUpperCase().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(
                        canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertThat(hash).isEqualTo(expected);
    }
}
