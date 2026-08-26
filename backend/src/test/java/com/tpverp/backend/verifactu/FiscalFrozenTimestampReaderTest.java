package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class FiscalFrozenTimestampReaderTest {

    @Test
    void preservesTheOffsetPersistedInTheFrozenXml() {
        var timestamp = FiscalFrozenTimestampReader.read(
                "<r:Evento xmlns:r=\"urn:test\"><r:FechaHoraHusoGenEvento>"
                        + "2026-08-26T09:10:11+05:30</r:FechaHoraHusoGenEvento></r:Evento>");

        assertThat(timestamp).isEqualTo(OffsetDateTime.parse("2026-08-26T09:10:11+05:30"));
    }

    @Test
    void rejectsAmbiguousOrExternalTimestampXml() {
        assertThatThrownBy(() -> FiscalFrozenTimestampReader.read(
                "<!DOCTYPE r [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>"
                        + "<r><FechaHoraHusoGenEvento>&x;</FechaHoraHusoGenEvento></r>"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> FiscalFrozenTimestampReader.read(
                "<r><FechaHoraHusoGenEvento>2026-08-26T09:10:11Z</FechaHoraHusoGenEvento>"
                        + "<FechaHoraHusoGenEvento>2026-08-26T09:10:12Z</FechaHoraHusoGenEvento></r>"))
                .isInstanceOf(IllegalStateException.class);
    }
}
