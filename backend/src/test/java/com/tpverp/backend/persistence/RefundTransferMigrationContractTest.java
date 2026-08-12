package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RefundTransferMigrationContractTest {

    @Test
    void allowsTraceableTransferRefundsWithoutTreatingThemAsCardOperations()
            throws Exception {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V142__devolucion_por_transferencia.sql")) {
            assertThat(stream).isNotNull();
            var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase();

            assertThat(sql)
                    .contains("'cash', 'card', 'voucher', 'transfer', 'exchange'")
                    .contains("tipo = 'transfer'")
                    .contains("terminal_operacion_id is null")
                    .contains("documento_pago_original_id is not null")
                    .contains("nullif(btrim(referencia), '') is not null");
        }
    }
}
