package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.backend.security.sales.SaleOperationCode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RefundTenderAuthorizationMigrationContractTest {

    @Test
    void migrationSupportsTraceableManualCardRefundsAndTenderOverrideAuthorization()
            throws Exception {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V133__devolucion_manual_y_autorizacion_medio.sql")) {
            assertThat(stream).isNotNull();
            var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase();

            assertThat(sql)
                    .contains("drop constraint if exists chk_documento_devolucion_pago_terminal")
                    .contains("documento_pago_original_id is not null")
                    .contains("nullif(btrim(referencia), '') is not null")
                    .contains("tipo in ('cash', 'voucher')")
                    .contains("drop constraint if exists intento_autorizacion_operacion_venta_codigo_ck")
                    .contains("'refund_policy_override'")
                    .contains("'refund_tender_override'");

        }
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V139__devolucion_factura_venta.sql")) {
            assertThat(stream).isNotNull();
            var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase();
            for (var operationCode : SaleOperationCode.values()) {
                assertThat(sql).contains("'" + operationCode.name().toLowerCase() + "'");
            }
        }
    }
}
