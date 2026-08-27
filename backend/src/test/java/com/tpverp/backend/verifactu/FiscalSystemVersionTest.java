package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FiscalSystemVersionTest {

    @Test
    void comparesHistoricalLowerCaseDeclarationHashWithoutMutatingIt() throws Exception {
        var version = new FiscalSystemVersion(
                UUID.randomUUID(), UUID.randomUUID(), "B12345674", "TPV ERP SL",
                "TPV ERP", "TPVERP", "4.1.0", "INST-1", "A".repeat(64),
                false, Instant.parse("2026-08-25T10:00:00Z"));
        var field = FiscalSystemVersion.class.getDeclaredField("declarationHash");
        field.setAccessible(true);
        field.set(version, "a".repeat(64));

        assertThat(version.matches(
                "B12345674", "TPV ERP SL", "TPV ERP", "TPVERP", "4.1.0",
                "INST-1", "A".repeat(64), false)).isTrue();
        assertThat(version.getDeclarationHash()).isEqualTo("a".repeat(64));
    }
}
