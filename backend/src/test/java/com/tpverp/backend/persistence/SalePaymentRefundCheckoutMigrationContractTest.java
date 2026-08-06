package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SalePaymentRefundCheckoutMigrationContractTest {

    @Test
    void migrationSupportsSignedDocumentsWithNonNegativeSettlementAmounts() throws Exception {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V131__checkout_devoluciones_y_total_cero.sql")) {
            assertThat(stream).isNotNull();
            var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase();

            assertThat(sql)
                    .contains("check (total >= 0)")
                    .contains("add column direction varchar(16) not null default 'sale'")
                    .contains("add column original_payment_id uuid references documento_pago(id)")
                    .contains("idx_sale_payment_allocation_original_payment");
        }
    }
}
