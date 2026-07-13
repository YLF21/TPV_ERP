package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaymentTerminalV47MigrationContractTest {
    @Test
    void persistsSanitizedReceiptsAndAppendOnlyReconciliationEvidence() throws Exception {
        var sql = Files.readString(Path.of("src/main/resources/db/migration/V47__payment_terminal_receipts_reconciliation.sql"));

        assertThat(sql).contains("CREATE TABLE payment_terminal_receipt",
                "CREATE TABLE payment_terminal_reconciliation_batch",
                "CREATE TABLE payment_terminal_reconciliation_event",
                "erp_total NUMERIC(19,2)", "provider_total NUMERIC(19,2)",
                "discrepancy NUMERIC(19,2)",
                "company_id UUID NOT NULL REFERENCES empresa(id)",
                "idx_payment_terminal_reconciliation_scope",
                "trg_payment_terminal_reconciliation_event_append_only");
        assertThat(sql.toLowerCase()).doesNotContain(" pan ", "pan_", "cvv", "pin_code", "secret_");
    }
}
