package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MigrationV77ContractTest {

    @Test
    void migrationAddsIdempotencyWithoutInventingPendingPaymentMethod() throws Exception {
        var sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V77__customer_pending_sales.sql"));
        var normalized = sql.toLowerCase();

        assertThat(normalized).contains(
                "customer_pending_sale_checkout", "request_hash", "documento_pago", "request_id");
        assertThat(normalized).contains("unique (terminal_id, checkout_id)", "unique (request_id)");
        assertThat(normalized).doesNotContain("insert into metodo_pago");
    }
}
