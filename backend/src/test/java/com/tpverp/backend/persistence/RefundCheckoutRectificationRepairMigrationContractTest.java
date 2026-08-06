package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RefundCheckoutRectificationRepairMigrationContractTest {

    @Test
    void migrationRebuildsMissingRectificationRelationsFromImmutableLineOrigins()
            throws Exception {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V132__repara_relaciones_devoluciones_checkout.sql")) {
            assertThat(stream).isNotNull();
            var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase();

            assertThat(sql)
                    .contains("insert into documento_relacion")
                    .contains("refund_line.original_document_line_id")
                    .contains("'rectifica'")
                    .contains("refund_document.total <= 0")
                    .contains("on conflict (documento_id, origen_id) do nothing");
        }
    }
}
