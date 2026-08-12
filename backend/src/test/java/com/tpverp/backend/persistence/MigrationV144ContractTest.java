package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV144ContractTest {

    @Test
    void permitsDraftSaveAndConfirmationCheckoutsForTheSameDocument() throws Exception {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V144__checkout_multiple_por_borrador_venta.sql")) {
            assertThat(stream).isNotNull();
            var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase();

            assertThat(sql)
                    .contains("alter table customer_pending_sale_checkout")
                    .contains("drop constraint if exists customer_pending_sale_checkout_document_id_key")
                    .contains("create index if not exists idx_customer_pending_sale_checkout_document")
                    .contains("on customer_pending_sale_checkout(document_id)")
                    .doesNotContain("drop table")
                    .doesNotContain("delete from")
                    .doesNotContain("update customer_pending_sale_checkout");
        }
    }
}
