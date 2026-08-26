package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class MigrationV209ContractTest {

    @Test
    void retiresOnlyUnprovenCompanyAndStoreFiscalTemplates() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V209__retire_unproven_custom_fiscal_templates.sql")) {
            assertThat(input).isNotNull();
            var sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);

            assertThat(sql)
                    .contains("'fiscal_visual_validation_required'")
                    .contains("ambito in ('company', 'store')")
                    .contains("tipo in ('ticket', 'factura_venta', 'rectificativa_venta')")
                    .contains("set origen = 'integrated'")
                    .contains("set estado = 'retired'")
                    .doesNotContain("delete from plantilla_documento")
                    .doesNotContain("ambito = 'system'");
        }
    }
}
