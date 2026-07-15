package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PosCashV62MigrationContractTest {
    @Test
    void persistsScopedCashCheckoutIdempotencyAndConfirmedResult() throws Exception {
        var sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V62__pos_cash_checkout_idempotency.sql"))
                .toLowerCase();

        assertThat(sql).contains(
                "pos_cash_checkout",
                "checkout_id",
                "company_id",
                "store_id",
                "terminal_id",
                "user_id",
                "request_hash",
                "ticket_snapshot jsonb",
                "documento_id",
                "unique(company_id,store_id,terminal_id,user_id,checkout_id)");
    }
}
