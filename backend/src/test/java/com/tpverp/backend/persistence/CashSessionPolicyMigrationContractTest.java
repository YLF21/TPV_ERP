package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CashSessionPolicyMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V104__politica_sesion_caja.sql";

    @Test
    void addsStoreCashSessionPolicyWithAutomaticOpeningAsDefault() throws Exception {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertThat(sql).contains(
                    "alter table configuracion_caja_tienda",
                    "sesion_caja_obligatoria boolean not null default false");
        }
    }
}
