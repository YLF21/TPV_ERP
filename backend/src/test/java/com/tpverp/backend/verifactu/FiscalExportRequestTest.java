package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class FiscalExportRequestTest {

    @Test
    void conservaCompatibilidadSinPeriodo() {
        var request = new FiscalExportRequest(FiscalExportKind.BILLING);

        assertThat(request.periodStart()).isNull();
        assertThat(request.periodEnd()).isNull();
        assertThat(request.hasValidPeriod()).isTrue();
    }

    @Test
    void exigeAmbosLimitesYOrdenCronologico() {
        var start = OffsetDateTime.parse("2026-08-01T00:00:00+01:00");
        var end = OffsetDateTime.parse("2026-08-31T23:59:59+01:00");

        assertThat(new FiscalExportRequest(FiscalExportKind.EVENTS, start, end)
                .hasValidPeriod()).isTrue();
        assertThat(new FiscalExportRequest(FiscalExportKind.EVENTS, start, null)
                .hasValidPeriod()).isFalse();
        assertThat(new FiscalExportRequest(FiscalExportKind.EVENTS, end, start)
                .hasValidPeriod()).isFalse();
    }
}
