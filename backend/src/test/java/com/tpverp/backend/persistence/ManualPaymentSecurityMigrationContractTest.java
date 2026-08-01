package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ManualPaymentSecurityMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V116__ampliar_seguridad_pagos_manuales.sql";

    @Test
    void extendsTheExistingOperationConstraintWithoutRewritingV112()
            throws IOException {
        var sql = migrationSql().toLowerCase();

        assertThat(sql)
                .contains("drop constraint config_seguridad_operacion_venta_codigo_ck")
                .contains("add constraint config_seguridad_operacion_venta_codigo_ck")
                .contains("'confirm_manual_card_payment'")
                .contains("'confirm_transfer_payment'")
                .contains("'payment_compensation_ack'");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader()
                .getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
