package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV178ContractTest {

    @Test
    void configuraTodasLasPresentacionesOperativasComoIntegradas() throws Exception {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V178__plantillas_integradas_por_defecto.sql")) {
            assertThat(stream).isNotNull();
            var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(sql)
                    .contains("('FACTURA_VENTA', 'A4')")
                    .contains("('TICKET', 'TICKET_80')")
                    .contains("('ALBARAN_VENTA', 'A4')")
                    .contains("('FACTURA_VENTA', 'TICKET_80')")
                    .contains("('VALE', 'TICKET_80')")
                    .contains("'INTEGRATED'")
                    .contains("on conflict (tienda_id, tipo, formato) do update")
                    .contains("version = configuracion_origen_plantilla_documento.version + 1");
        }
    }
}
