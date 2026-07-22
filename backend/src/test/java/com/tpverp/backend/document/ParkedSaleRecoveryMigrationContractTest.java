package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ParkedSaleRecoveryMigrationContractTest {

    @Test
    void storesScopedIdempotentRecoveryClaimsAndAcknowledgements() throws Exception {
        var sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V88__parked_sale_recovery.sql"))
                .toLowerCase();

        assertThat(sql).contains(
                "venta_aparcada_recuperacion",
                "recovery_id uuid primary key",
                "venta_aparcada_id uuid not null unique",
                "tienda_id uuid not null",
                "empresa_id uuid not null",
                "foreign key (tienda_id, empresa_id) references tienda(id, empresa_id)",
                "check (estado in ('claimed', 'acknowledged'))",
                "estado = 'claimed' and confirmado_en is null",
                "estado = 'acknowledged' and confirmado_en is not null");
    }
}
