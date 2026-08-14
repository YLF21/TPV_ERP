package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV147ContractTest {

    @Test
    void retiresOnlyBundledActiveSystemTemplatesAndPreservesHistory() throws Exception {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V147__retirar_plantillas_jrxml_automaticas.sql")) {
            assertThat(stream).isNotNull();
            var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase();

            assertThat(sql)
                    .contains("update plantilla_documento")
                    .contains("estado = 'retired'")
                    .contains("ambito = 'system'")
                    .contains("estado = 'active'")
                    .contains("'factura_a4'")
                    .contains("'albaran_a4'")
                    .contains("'factura_ticket_80'")
                    .doesNotContain("version_plantilla")
                    .doesNotContain("delete from")
                    .doesNotContain("drop table");
        }
    }
}
