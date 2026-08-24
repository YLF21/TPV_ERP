package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FiscalPrintSnapshotFactoryTest {

    private final FiscalPrintSnapshotFactory factory =
            new FiscalPrintSnapshotFactory(new FiscalQrUrlService());

    @Test
    void freezesVerifactuTestQrAndPrintLegend() throws Exception {
        var snapshot = factory.create(
                record(), FiscalMode.VERIFACTU, FiscalEndpointEnvironment.TEST, "dev-build-1");

        assertThat(snapshot.formatVersion()).isEqualTo("AEAT_QR_0.5.0");
        assertThat(snapshot.generatorVersion()).isEqualTo("dev-build-1");
        assertThat(snapshot.qrUrl()).startsWith(
                "https://prewww2.aeat.es/wlpl/TIKE-CONT/ValidarQR?");
        assertThat(snapshot.qrUrl()).contains("nif=B12345674");
        assertThat(snapshot.qrUrl()).contains("numserie=FV-001-26-000001");
        assertThat(snapshot.qrUrl()).contains("fecha=02-06-2026&importe=157.26");
        assertThat(snapshot.prefix()).isEqualTo(FiscalPrintSnapshotFactory.PREFIX);
        assertThat(snapshot.legend()).isEqualTo(FiscalPrintSnapshotFactory.VERIFACTU_LEGEND);
        assertThat(snapshot.testNotice()).isEqualTo(FiscalPrintSnapshotFactory.TEST_NOTICE);
        assertThat(snapshot.qrPayloadSha256()).isEqualTo(sha256(snapshot.qrUrl()));
    }

    @Test
    void freezesNoVerifactuProductionQrWithoutVerifiableLegend() throws Exception {
        var snapshot = factory.create(
                record(), FiscalMode.NO_VERIFACTU, FiscalEndpointEnvironment.PRODUCTION,
                "release-1");

        assertThat(snapshot.qrUrl()).startsWith(
                "https://www2.agenciatributaria.gob.es/wlpl/TIKE-CONT/ValidarQRNoVerifactu?");
        assertThat(snapshot.qrUrl()).contains("nif=B12345674");
        assertThat(snapshot.qrUrl()).contains("numserie=FV-001-26-000001");
        assertThat(snapshot.legend()).isNull();
        assertThat(snapshot.testNotice()).isNull();
        assertThat(snapshot.qrPayloadSha256()).isEqualTo(sha256(snapshot.qrUrl()));
    }

    private FiscalRecord record() {
        return new FiscalRecord(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, FiscalRecordOperation.ALTA, FiscalDocumentType.F1,
                "FV-001-26-000001", LocalDate.of(2026, 6, 2),
                Instant.parse("2026-06-02T10:00:00Z"), "Atlantic/Canary", "B12345674",
                new BigDecimal("27.30"), new BigDecimal("157.26"), null,
                "A".repeat(64), "B".repeat(64), Map.of("numero", "FV-001-26-000001"),
                "VERIFACTU-1", "AEAT-SHA256-1", "TPV-ERP-0.0.1");
    }

    private static String sha256(String value) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        var result = new StringBuilder(64);
        for (byte current : digest) {
            result.append(String.format("%02X", current));
        }
        return result.toString();
    }
}
