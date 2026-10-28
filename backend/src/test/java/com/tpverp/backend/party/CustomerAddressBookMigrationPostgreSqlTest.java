package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Targeted upgrade-data check; the full migration chain is covered by the JPA flow tests. */
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
class CustomerAddressBookMigrationPostgreSqlTest {
    @Test void upgradeKeepsExistingDefaultNotesAndCreditAndSupportsLegacyInserts() throws Exception {
        String schema = "customer_address_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getenv("TPV_ERP_TEST_DB_URL"),
                System.getenv("TPV_ERP_TEST_DB_USER"), System.getenv("TPV_ERP_TEST_DB_PASSWORD"));
             var statement = connection.createStatement()) {
            statement.execute("create schema " + schema);
            try {
                statement.execute("set search_path to " + schema);
                statement.execute("""
                    create table cliente(id uuid primary key, direccion varchar(255), codigo_postal varchar(16),
                        poblacion varchar(128), provincia varchar(128), pais varchar(2), telefono varchar(64),
                        email varchar(320), observaciones text, credit_enabled boolean not null default true);
                    insert into cliente values ('00000000-0000-0000-0000-000000000001', 'Calle Ñ 1', '35001',
                        'Las Palmas', 'Las Palmas', 'ES', '111', 'test@example.invalid', 'Notas 中文', false);
                    """);
                statement.execute(Files.readString(Path.of("src/main/resources/db/migration/V280__customer_shared_address_book.sql")));
                try (var row = statement.executeQuery("""
                        select addresses_json->0->'address'->>'address' as street,
                            addresses_json->0->>'notes' as notes, credit_enabled, address_sync_status,
                            saas_address_revision from cliente
                        """)) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString("street")).isEqualTo("Calle Ñ 1");
                    assertThat(row.getString("notes")).isEqualTo("Notas 中文");
                    assertThat(row.getBoolean("credit_enabled")).isFalse();
                    assertThat(row.getString("address_sync_status")).isEqualTo("LOCAL");
                    assertThat(row.getObject("saas_address_revision")).isNull();
                }
                statement.execute("insert into cliente(id, direccion) values ('00000000-0000-0000-0000-000000000002', 'Legacy insert')");
                try (var row = statement.executeQuery("select addresses_json->0->'address'->>'address', credit_enabled from cliente where id='00000000-0000-0000-0000-000000000002'")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString(1)).isEqualTo("Legacy insert");
                    assertThat(row.getBoolean(2)).isTrue();
                }
            } finally {
                if (!schema.matches("customer_address_upgrade_[0-9a-f]{32}")) throw new IllegalStateException("Unsafe test schema");
                statement.execute("drop schema " + schema + " cascade");
            }
        }
    }
}
