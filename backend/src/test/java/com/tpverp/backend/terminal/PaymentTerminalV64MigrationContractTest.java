package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaymentTerminalV64MigrationContractTest {
    @Test
    void marksVersionDriftedActiveLegacyOperationsForManualReview() throws Exception {
        var sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V64__separate_payment_pairing_metadata_from_operational_version.sql"))
                .toLowerCase();

        assertThat(sql)
                .contains("legacy_configuration_fingerprint boolean not null default true")
                .contains("alter column legacy_configuration_fingerprint set default false")
                .contains("set status = 'review_required'")
                .contains("operation.configuration_version <> configuration.operational_version")
                .contains("operation.status in ('pending', 'sent', 'timeout')")
                .contains("insert into payment_terminal_event")
                .contains("v61_legacy_version_drift");
    }
}
