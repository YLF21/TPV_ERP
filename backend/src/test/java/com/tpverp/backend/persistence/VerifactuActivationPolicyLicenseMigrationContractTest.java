package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class VerifactuActivationPolicyLicenseMigrationContractTest {

    private static final String MIGRATION = "db/migration/V95__verifactu_activation_policy_license.sql";

    @Test
    void storesVersionedSaasFiscalPolicyOnTheLocalLicense() throws IOException {
        String sql = migrationSql();

        assertThat(sql).contains(
                "add column verifactu_activation_date date",
                "add column verifactu_policy_version bigint",
                "add column verifactu_policy_updated_at timestamp with time zone",
                "verifactu_policy_version is null or verifactu_policy_version >= 0");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
