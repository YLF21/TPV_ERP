package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FiscalRecordServiceTest {

    @Test
    void avanzaLaCabezaDeUnaCadenaVacia() {
        var companyId = UUID.randomUUID();
        var installationId = UUID.randomUUID();
        var chain = new FiscalChain(
                companyId, installationId, Instant.parse("2026-06-14T09:00:00Z"));
        var record = new FiscalRecord(
                chain.getId(),
                companyId,
                installationId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                FiscalRecordOperation.ALTA,
                FiscalDocumentType.F2,
                "001-260614-000001",
                LocalDate.of(2026, 6, 14),
                Instant.parse("2026-06-14T10:00:00Z"),
                "Atlantic/Canary",
                "B12345678",
                new BigDecimal("2.10"),
                new BigDecimal("12.10"),
                null,
                "A".repeat(64),
                "B".repeat(64),
                Map.of("numero", "001-260614-000001"),
                "1.0",
                "SHA-256",
                "0.0.1");
        var updatedAt = Instant.parse("2026-06-14T10:00:01Z");

        chain.advance(record, updatedAt);

        assertThat(chain.nextSequence()).isEqualTo(2);
        assertThat(chain.previousHash()).isEqualTo(record.getHash());
        assertThat(chain.getLastRecord()).isSameAs(record);
        assertThat(chain.getUpdatedAt()).isEqualTo(updatedAt);
    }
}
