package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MemberDocumentLoyaltySettlementMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V127__liquidacion_historica_fidelizacion.sql";

    @Test
    void storesHistoricalSettlementAndLineEligibilityWithIntegrityChecks()
            throws IOException {
        var sql = migrationSql();

        assertThat(sql)
                .contains("create table member_document_loyalty_settlement")
                .contains("documento_id uuid primary key references documento(id)")
                .contains("eligible_paid_amount numeric(19,2) not null default 0")
                .contains("generated_points = granted_points + points_applied_to_debt")
                .contains("generated_balance = granted_balance + balance_applied_to_debt")
                .contains("create table member_document_loyalty_line")
                .contains("documento_linea_id uuid primary key references documento_linea(id)")
                .contains("eligible boolean not null");
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
