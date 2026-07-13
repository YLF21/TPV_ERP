package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaymentTerminalMigrationContractTest {

    @Test
    void migrationDefinesRecoverableOperationLedgerAndV56Backfill() throws Exception {
        var sql = Files.readString(Path.of("src/main/resources/db/migration/V57__payment_terminal_operations.sql"));

        assertThat(sql).contains(
                "CREATE TABLE payment_terminal_operation",
                "provider VARCHAR(32) NOT NULL",
                "mode VARCHAR(16) NOT NULL",
                "operation_type VARCHAR(16) NOT NULL",
                "original_operation_id UUID",
                "idempotency_key VARCHAR(128) NOT NULL",
                "refunded_amount NUMERIC(19,2) NOT NULL DEFAULT 0",
                "processing_owner UUID",
                "processing_lease_until TIMESTAMPTZ",
                "CREATE TABLE payment_terminal_event",
                "CREATE TRIGGER trg_payment_terminal_event_append_only",
                "INSERT INTO payment_terminal_operation",
                "FROM pos_card_checkout checkout",
                "REDSYS_TPV_PC",
                "authorization_code");
        assertThat(sql).contains("UNIQUE (terminal_id, idempotency_key)");
        assertThat(sql).contains("configuration_hash VARCHAR(64)", "configuration_version = -1",
                "ON payment_terminal_event(operation_id, created_at, id)");
        assertThat(sql).doesNotContain("PAN", "CVV", "pin_code");
    }
}
