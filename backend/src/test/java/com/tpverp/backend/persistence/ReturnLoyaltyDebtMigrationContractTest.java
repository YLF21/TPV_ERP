package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ReturnLoyaltyDebtMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V126__deuda_fidelizacion_y_credito_devolucion.sql";

    @Test
    void addsSeparateDebtAndProtectedInternalReturnCredit() throws IOException {
        var sql = migrationSql();

        assertThat(sql)
                .contains("loyalty_balance_debt numeric(19,2) not null default 0")
                .contains("loyalty_points_debt bigint not null default 0")
                .contains("check (loyalty_balance_debt >= 0)")
                .contains("check (loyalty_points_debt >= 0)")
                .contains("'devolucion_acumulacion_puntos'")
                .contains("'pago_deuda_saldo'")
                .contains("'credito_devolucion', true, true, false, false");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader()
                .getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase();
        }
    }
}
