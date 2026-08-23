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
}
