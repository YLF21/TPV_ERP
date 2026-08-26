package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FiscalExportJobRequestTest {
    @Test
    void scopeIsExplicitAndCombinationsAreValidated() {
        var start = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        var end = OffsetDateTime.parse("2026-01-31T23:59:59Z");
        assertThat(new FiscalExportJobRequest(FiscalExportKind.EVENTS, start, end, List.of(),
                null, null, null, null, null, null, null, FiscalExportJobScope.PERIOD)
                .hasValidScope()).isTrue();
        assertThat(new FiscalExportJobRequest(FiscalExportKind.BILLING, null, null,
                List.of(UUID.randomUUID()), null, null, null, null, null, null, null,
                FiscalExportJobScope.CURRENT).hasValidScope()).isTrue();
        assertThat(new FiscalExportJobRequest(FiscalExportKind.EVENTS, null, null, List.of(),
                null, null, null, null, null, null, null, FiscalExportJobScope.CURRENT)
                .hasValidScope()).isFalse();
    }
}
