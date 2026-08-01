package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SaleDocumentAuthorizationManifestMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V117__manifiesto_autorizacion_documento_venta.sql";

    @Test
    void persistsDocumentFingerprintAndPolicyVersionsWithoutCredentials()
            throws IOException {
        var sql = migrationSql();

        assertThat(sql)
                .contains("create table manifiesto_autorizacion_documento_venta")
                .contains("documento_id uuid primary key")
                .contains("version_formato integer not null default 1")
                .contains("algoritmo varchar(16) not null default 'sha-256'")
                .contains("huella varchar(64) not null")
                .contains("create table manifiesto_autorizacion_documento_venta_operacion")
                .contains("codigo_operacion varchar(64) not null")
                .contains("version_politica bigint not null")
                .contains("foreign key (documento_id) references documento (id)")
                .doesNotContain("password")
                .doesNotContain("contrasena")
                .doesNotContain("contraseña");
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
